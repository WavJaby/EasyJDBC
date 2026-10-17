package com.wavjaby.jdbc.processor.emit;

import com.squareup.javapoet.*;
import com.wavjaby.jdbc.processor.EmptyProcessingException;
import com.wavjaby.jdbc.processor.model.*;
import com.wavjaby.jdbc.util.FastResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.util.*;

import static com.wavjaby.jdbc.processor.emit.PostgresDialect.quoteColumn;
import static com.wavjaby.jdbc.processor.emit.PostgresDialect.quoteTable;
import static javax.tools.Diagnostic.Kind.ERROR;

/** Writes one table's repository implementation: a method per MethodInfo, chosen by its Operation. */
public class RepositoryEmitter {
    private static final String TABLE_MAPPER = "tableMapper";

    private final Messager console;
    private final Filer filer;
    private final Elements elementUtils;

    public RepositoryEmitter(Messager console, Filer filer, Elements elementUtils) {
        this.console = console;
        this.filer = filer;
        this.elementUtils = elementUtils;
    }

    public void emit(TableData tableData) throws EmptyProcessingException {
        generateFile(tableData);
    }

    private void generateFile(TableData tableData) throws EmptyProcessingException {
        TableInfo tableInfo = tableData.tableInfo;

        // Create class Builder
        ClassName repoClassName = ClassName.get(tableInfo.classPackagePath, tableInfo.repoClassName);
        ClassName tableDataClass = ClassName.get(tableInfo.classPackagePath, tableInfo.className);

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(repoClassName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Repository.class)
                .addSuperinterface(tableInfo.repoIntClassElement.asType());

        // Fields
        typeBuilder.addField(FieldSpec.builder(JdbcTemplate.class, "jdbc")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build());

        // FastResultSetExtractor field
        ParameterizedTypeName tableMapperType = ParameterizedTypeName.get(
                ClassName.get(FastResultSetExtractor.class),
                tableDataClass);
        typeBuilder.addField(FieldSpec.builder(tableMapperType, TABLE_MAPPER)
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build());

        // One extractor per projection record, shared by every method returning it
        Map<String, ReturnInfo.Projection> projections = new LinkedHashMap<>();
        for (MethodInfo method : tableData.interfaceMethodInfo)
            if (method.returns.projection() != null)
                projections.putIfAbsent(method.returns.projection().type().getQualifiedName().toString(), method.returns.projection());
        Map<String, String> projectionMappers = projectionMapperFields(projections.values(), tableData);
        for (ReturnInfo.Projection projection : projections.values()) {
            ClassName projectionClass = ClassName.get(projection.type());
            typeBuilder.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(FastResultSetExtractor.class), projectionClass),
                            projectionMappers.get(projection.type().getQualifiedName().toString()))
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                    .build());
        }

        // Constructor
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(JdbcTemplate.class, "jdbc")
                .addStatement("this.jdbc = jdbc");

        // Add repository dependency
        generateClassDependencies(tableInfo, tableData, typeBuilder, constructorBuilder);

        // Finish constructor
        constructorBuilder.addStatement("$N = new $T<>($T.class, $L)",
                TABLE_MAPPER,
                FastResultSetExtractor.class,
                tableDataClass,
                tableData.tableColumns.size());
        for (ReturnInfo.Projection projection : projections.values())
            constructorBuilder.addStatement("$N = new $T<>($T.class, $L)",
                    projectionMappers.get(projection.type().getQualifiedName().toString()),
                    FastResultSetExtractor.class,
                    ClassName.get(projection.type()),
                    projection.columns().size());

        typeBuilder.addMethod(constructorBuilder.build());

        // Add repository method
        generateRepositoryMethods(tableData, typeBuilder, projectionMappers);

        try {
            JavaFile.builder(tableInfo.classPackagePath, typeBuilder.build())
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            console.printMessage(ERROR, "Could not write class: '" + tableInfo.repoClassPath + "'. Error: " + e);
            throw new EmptyProcessingException();
        }
    }


    // Projection qualified name -> extractor field name: simple name + "Mapper", suffixed when two projections or a dependency field would collide
    private static Map<String, String> projectionMapperFields(Collection<ReturnInfo.Projection> projections, TableData tableData) {
        Set<String> taken = new HashSet<>(tableData.getDependencies().values());
        taken.add("jdbc");
        taken.add(TABLE_MAPPER);
        Map<String, String> fields = new LinkedHashMap<>();
        for (ReturnInfo.Projection projection : projections) {
            TypeElement type = projection.type();
            String qualifiedName = type.getQualifiedName().toString();
            String simpleName = type.getSimpleName().toString();
            String base = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1) + "Mapper";
            String name = base;
            for (int i = 2; !taken.add(name); i++)
                name = base + i;
            fields.put(qualifiedName, name);
        }
        return fields;
    }

    private void generateRepositoryMethods(TableData tableData, TypeSpec.Builder typeBuilder, Map<String, String> projectionMappers) {
        for (MethodInfo method : tableData.interfaceMethodInfo) {
            switch (method.operation) {
                case INSERT -> generateRepositoryInsertMethod(method, tableData, typeBuilder);
                case UPDATE -> generateRepositoryUpdateMethod(method, tableData, typeBuilder, false);
                case UPDATE_CHECKED -> generateRepositoryUpdateMethod(method, tableData, typeBuilder, true);
                case DELETE -> generateRepositoryDeleteMethod(method, tableData, typeBuilder);
                case COUNT -> generateRepositoryCountMethod(method, tableData, typeBuilder);
                case EXISTS -> generateRepositoryCheckMethod(method, tableData, typeBuilder);
                case SELECT_ROWS -> generateRepositorySearchMethod(method, tableData, typeBuilder);
                case SELECT_PROJECTION -> generateRepositoryProjectionMethod(method, tableData, typeBuilder,
                        projectionMappers.get(method.returns.projection().type().getQualifiedName().toString()));
                case SELECT_COLUMN -> generateRepositorySearchColumnMethod(method, tableData, typeBuilder);
            }
        }
    }


    private void generateRepositoryInsertMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        boolean returnInt = methodInfo.returnTypeMirror.getKind() == TypeKind.INT;
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        List<MethodParamInfo> infos = new ArrayList<>(methodInfo.params);

        CodeBlock idGenerator = JdbcCodeGenerator.addIdGenerator(tableData, infos);

        JdbcCodeGenerator.QueryAndArgs values = JdbcCodeGenerator.insert(infos, methodInfo);

        if (methodInfo.batchInsert) {
            MethodParamInfo param = methodInfo.params.get(0);
            String typeStr = ((DeclaredType) param.parameter.asType()).asElement().getSimpleName().toString();

            methodBuilder.addStatement("$T<Object[]> batchValues = new $T<>($L_.size())",
                    List.class, ArrayList.class, param.paramName);
            methodBuilder.beginControlFlow("for ($L $L : $L_)", typeStr, param.paramName, param.paramName);
            methodBuilder.addCode(idGenerator);
            methodBuilder.addStatement("batchValues.add(new Object[]{$L})", CodeBlock.join(values.args(), ", "));
            methodBuilder.endControlFlow();

            methodBuilder.addStatement("int[] result = jdbc.batchUpdate($S, batchValues)", "INSERT INTO " + quoteTable(tableInfo) + values.query());

            if (returnInt)
                methodBuilder.addStatement("return $T.stream(result).sum()", Arrays.class);
        } else {
            methodBuilder.addCode(idGenerator);
            String sql = "INSERT INTO " + quoteTable(tableInfo) + values.query();

            if (returnInt && !methodInfo.returns.table())
                methodBuilder.addStatement("return ");

            if (values.args().isEmpty()) {
                methodBuilder.addStatement("jdbc.update($S)", sql);
            } else {
                methodBuilder.addStatement("jdbc.update($S, $L)", sql, CodeBlock.join(values.args(), ", "));
            }

            if (methodInfo.returns.table()) {
                methodBuilder.addStatement("return new $T($L)", ClassName.bestGuess(tableInfo.className), CodeBlock.join(JdbcCodeGenerator.rowArgs(infos), ", "));
            }
        }

        typeBuilder.addMethod(methodBuilder.build());
    }


    private void generateRepositoryCheckMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.where(methodInfo.params, methodInfo);

        String sql = "SELECT COUNT(*) FROM " + quoteTable(tableInfo) + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcQueryObject(methodBuilder, sql, queryWithArgs.args(), int.class, true);

        typeBuilder.addMethod(methodBuilder.build());
    }

    private void generateRepositoryCountMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.where(methodInfo.params, methodInfo);

        String sql = "SELECT COUNT(*) FROM " + quoteTable(tableInfo) + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcQueryObject(methodBuilder, sql, queryWithArgs.args(), int.class, false);

        typeBuilder.addMethod(methodBuilder.build());
    }

    private void generateRepositorySearchColumnMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        StringBuilder columnQuery = new StringBuilder();
        List<CodeBlock> columnArgs = new ArrayList<>();
        ReturnInfo.ReturnColumn returnColumn = methodInfo.returns.column();
        if (returnColumn.columnSqlParams() != null) {
            for (SqlParamInfo param : returnColumn.columnSqlParams()) {
                columnQuery.append(param.sqlPart());
                if (param.paramName() != null) {
                    columnQuery.append('?');
                    MethodParamInfo methodParam = param.methodParamInfo();
                    if (methodParam != null && methodParam.parameter.asType() instanceof ArrayType) {
                        columnArgs.add(CodeBlock.of("new $T($T.ARRAY, $L)",
                                org.springframework.jdbc.core.SqlParameterValue.class,
                                java.sql.Types.class,
                                param.paramName()
                        ));
                    } else {
                        columnArgs.add(CodeBlock.of("$L", param.paramName()));
                    }
                }
            }
        } else {
            columnQuery.append(quoteColumn(returnColumn.column().columnName));
        }

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.where(methodInfo.params, methodInfo);
        List<CodeBlock> sqlArgs;
        if (!columnArgs.isEmpty()) {
            sqlArgs = new ArrayList<>(columnArgs);
            sqlArgs.addAll(queryWithArgs.args());
        } else
            sqlArgs = queryWithArgs.args();

        String sql = "SELECT " + columnQuery + " FROM " + quoteTable(tableInfo) + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, sqlArgs, null);

        typeBuilder.addMethod(methodBuilder.build());
    }

    // Same as a row search, but SELECTs only the projection's columns, in component order -- the extractor fills the constructor by position
    private void generateRepositoryProjectionMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder, String mapperField) {
        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.where(methodInfo.params, methodInfo);

        StringBuilder columnQuery = new StringBuilder();
        for (ColumnInfo column : methodInfo.returns.projection().columns()) {
            if (!columnQuery.isEmpty()) columnQuery.append(',');
            columnQuery.append(quoteColumn(column.columnName));
        }

        String sql = "SELECT " + columnQuery + " FROM " + quoteTable(tableData.tableInfo) + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, queryWithArgs.args(), mapperField);

        typeBuilder.addMethod(methodBuilder.build());
    }

    private void generateRepositorySearchMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.where(methodInfo.params, methodInfo);

        StringBuilder columnQuery = new StringBuilder();
        boolean first = true;
        for (ColumnInfo column : tableData.tableColumns.values()) {
            if (!first) columnQuery.append(',');
            first = false;
            columnQuery.append(quoteColumn(column.columnName));
        }

        String sql = "SELECT " + columnQuery + " FROM " + quoteTable(tableInfo) + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, queryWithArgs.args(), TABLE_MAPPER);

        typeBuilder.addMethod(methodBuilder.build());
    }


    private void generateRepositoryDeleteMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.where(methodInfo.params, methodInfo);

        String sql = "DELETE FROM " + quoteTable(tableInfo) + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcUpdate(methodBuilder, sql, queryWithArgs.args(), methodInfo.returnTypeMirror);

        typeBuilder.addMethod(methodBuilder.build());
    }

    private void generateRepositoryUpdateMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder, boolean checkSuccess) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        List<MethodParamInfo> whereColumns = new ArrayList<>();
        List<MethodParamInfo> updateColumns = new ArrayList<>();
        // A plain parameter maps to one column unless @Where lists several, and then it is a WHERE parameter
        for (MethodParamInfo param : methodInfo.params) {
            if (param.where)
                whereColumns.add(param);
            else
                updateColumns.add(param);
        }

        JdbcCodeGenerator.QueryAndArgs update = JdbcCodeGenerator.update(whereColumns, updateColumns, methodInfo);

        String sql = "UPDATE " + quoteTable(tableInfo) + update.query();

        if (methodInfo.returns.table()) {
            if (update.args().isEmpty())
                methodBuilder.beginControlFlow("if (jdbc.update($S) == 1)", sql);
            else
                methodBuilder.beginControlFlow("if (jdbc.update($S, $L) == 1)", sql, CodeBlock.join(update.args(), ", "));

            JdbcCodeGenerator.QueryAndArgs where = JdbcCodeGenerator.where(whereColumns, null);

            StringBuilder columnQuery = new StringBuilder();
            boolean first = true;
            for (ColumnInfo column : tableData.tableColumns.values()) {
                if (!first) columnQuery.append(',');
                first = false;
                columnQuery.append(quoteColumn(column.columnName));
            }

            String selectSql = "SELECT " + columnQuery + " FROM " + quoteTable(tableInfo) + where.query();

            JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, selectSql, where.args(), TABLE_MAPPER);

            methodBuilder.endControlFlow();
            methodBuilder.addStatement("return null");

        } else {
            JdbcCodeGenerator.buildJdbcUpdate(methodBuilder, sql, update.args(), checkSuccess ? elementUtils.getTypeElement("java.lang.Boolean").asType() : methodInfo.returnTypeMirror);
        }

        typeBuilder.addMethod(methodBuilder.build());
    }

    private void generateClassDependencies(TableInfo tableInfo, TableData tableData, TypeSpec.Builder typeBuilder, MethodSpec.Builder constructorBuilder) {
        // Create field defamation
        for (Map.Entry<String, String> dependency : tableData.getDependencies().entrySet()) {
            String fullClassName = dependency.getKey();
            String fieldName = dependency.getValue();

            if (fieldName != null) {
                ClassName className = ClassName.bestGuess(fullClassName);

                typeBuilder.addField(FieldSpec.builder(className, fieldName)
                        .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                        .build());
                constructorBuilder.addParameter(className, fieldName);
                constructorBuilder.addStatement("this.$N = $N", fieldName, fieldName);
            }
        }
    }

}
