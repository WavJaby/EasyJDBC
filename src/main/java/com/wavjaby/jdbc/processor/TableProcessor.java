package com.wavjaby.jdbc.processor;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.processor.model.*;
import com.wavjaby.jdbc.processor.resolve.ResolvedTable;
import com.wavjaby.jdbc.processor.resolve.Resolver;
import com.wavjaby.jdbc.processor.util.JdbcCodeGenerator;
import com.wavjaby.jdbc.processor.util.ProcessorUtil;
import com.wavjaby.jdbc.processor.util.SqlGenerator;
import com.wavjaby.jdbc.util.FastResultSetExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationMirror;
import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationValueClassElement;
import static com.wavjaby.jdbc.processor.util.PostgresDialect.*;
import static javax.tools.Diagnostic.Kind.ERROR;


@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.wavjaby.jdbc.annotation.Table")
@SuppressWarnings("unused")
public class TableProcessor extends AbstractProcessor {
    private Messager console;
    private static Elements elementUtils;
    private static Types typeUtils;
    private static final String TABLE_MAPPER = "tableMapper";
    private Filer filer;

    @SuppressWarnings("unused")
    public TableProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
        typeUtils = processingEnv.getTypeUtils();
        this.console = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
    }

    public static Elements getElementUtils() {
        return elementUtils;
    }

    public static Types getTypeUtils() {
        return typeUtils;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {

            if (annotations.isEmpty())
                return false;

            Map<String, TableData> tableDataMap = new HashMap<>();
            for (TypeElement annotation : annotations) {
                for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    TableData tableData = parseTableData((TypeElement) element);
                    if (tableData == null) return false;
                    tableDataMap.put(tableData.tableInfo.classPath, tableData);
                }
            }

            Map<String, ResolvedTable> resolvedTables = Resolver.resolve(tableDataMap, console);
            if (resolvedTables == null)
                return false;

            boolean allSuccess = true;
            for (TableData tableData : tableDataMap.values())
                if (generateFile(tableData)) allSuccess = false;
            if (!allSuccess)
                return false;

            Map<String, ResolvedTable> tableDependency = new LinkedHashMap<>();
            for (ResolvedTable table : resolvedTables.values()) {
                processTableDependency(table, resolvedTables, tableDependency);
            }

            if (generateInitFile(tableDataMap, tableDependency))
                return false;

            if (ProcessorUtil.copyUtilityClasses(processingEnv, console))
                return false;

            System.out.println("RepositoryTemplate process done " + tableDataMap.size());
            return true;
        } catch (Exception e) {
            // An ERROR fails the build; returning false alone left the build green with nothing generated.
            // Full trace -> stderr; the diagnostic names the processor method that failed (the top frame is usually JDK code), no line number so it stays stable across edits
            e.printStackTrace();
            String processorPackage = TableProcessor.class.getPackageName();
            String where = Arrays.stream(e.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith(processorPackage))
                    .findFirst()
                    .map(frame -> " in " + frame.getClassName() + "." + frame.getMethodName())
                    .orElse("");
            console.printMessage(ERROR, "EasyJDBC processor internal error, a processor bug rather than a problem in your code: " + e + where);
            return false;
        }
    }

    // Post-order: every table or view lands after the ones its DDL names
    private void processTableDependency(ResolvedTable table, Map<String, ResolvedTable> resolvedTables, Map<String, ResolvedTable> dependency) {
        if (table.viewBase != null)
            processTableDependency(resolvedTables.get(table.viewBase.tableInfo.classPath), resolvedTables, dependency);
        for (ColumnInfo info : table.table.tableFields.values()) {
            ColumnInfo referencedColumn = table.referencedColumn(info);
            if (referencedColumn != null && !dependency.containsKey(info.referencedTableClassPath))
                processTableDependency(resolvedTables.get(referencedColumn.tableData.tableInfo.classPath), resolvedTables, dependency);
        }
        dependency.put(table.table.tableInfo.classPath, table);
    }

    private TableData parseTableData(TypeElement element) {
        // Get table repository class
        AnnotationMirror tableMirror = getAnnotationMirror(element, Table.class);
        assert tableMirror != null;
        TypeElement repoInterfaceClass = (TypeElement) getAnnotationValueClassElement(tableMirror, "repositoryClass");
        if (repoInterfaceClass == null) {
            console.printMessage(ERROR, "The 'repositoryClass' is not given", element);
            return null;
        }
        TypeElement virtualBaseClass = (TypeElement) getAnnotationValueClassElement(tableMirror, "virtualBaseClass");

        // Get table definition class name
        TableInfo tableInfo = new TableInfo(element, repoInterfaceClass, virtualBaseClass);
        TableData tableData = new TableData(tableInfo);
        if (tableData.parseTableField(console))
            return null;

        // Virtual table check
        if (tableInfo.isVirtual) {
            if (virtualBaseClass == null) {
                console.printMessage(ERROR, "Virtual table 'virtualBaseClass' is not given", element);
                return null;
            }
            if (tableData.tableFields.isEmpty()) {
                console.printMessage(ERROR, "Virtual table '" + tableInfo.name + "' must have at least one field", element);
            }
        }

        if (tableData.parseRepoMethods(tableData, console))
            return null;

        return tableData;
    }

    private boolean generateInitFile(Map<String, TableData> tableDataMap, Map<String, ResolvedTable> tableDependency) {
        String classPackage = "com.wavjaby.jdbc.util";
        String className = "RepositoryInit";

        FieldSpec logger = FieldSpec.builder(Logger.class, "logger")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($L.class)", LoggerFactory.class, className)
                .build();
        FieldSpec jdbc = FieldSpec.builder(JdbcTemplate.class, "jdbc")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(JdbcTemplate.class, "jdbc")
                .addStatement("this.jdbc = jdbc")
                .build();

        MethodSpec.Builder initSchemeAndTable = MethodSpec.methodBuilder("initSchemeAndTable")
                .addModifiers(Modifier.PUBLIC);

        Set<String> schemaList = new HashSet<>();
        for (TableData tableData : tableDataMap.values()) {
            if (tableData.tableInfo.schema == null) continue;
            schemaList.add(tableData.tableInfo.schema);
        }

        // Create schema init code
        for (String schema : schemaList) {
            initSchemeAndTable.addStatement("jdbc.execute($S)", "create schema if not exists " + schema + ";");
        }

        // Create enum type init code, keyed by type name: one CREATE TYPE per name however many columns use it
        Map<String, TypeElement> enumTypes = new LinkedHashMap<>();
        for (ResolvedTable table : tableDependency.values()) {
            for (ColumnInfo column : table.table.tableFields.values()) {
                if (column.nativeEnum) {
                    if (column.type instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement typeElement) {
                        enumTypes.putIfAbsent(enumTypeName(column.type), typeElement);
                    }
                }
            }
        }
        for (Map.Entry<String, TypeElement> entry : enumTypes.entrySet()) {
            List<String> enumValues = entry.getValue().getEnclosedElements().stream()
                    .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                    .map(e -> e.getSimpleName().toString())
                    .toList();
            String sql = createEnumTypeSql(entry.getKey(), enumValues);
            initSchemeAndTable.addStatement("jdbc.execute(\"\"\"\n$L\"\"\")", sql);
        }

        // Create table create SQL
        List<ResolvedTable> tables = new ArrayList<>(tableDependency.values());
        tables.sort((a, b) -> {
            if (a.table.tableInfo.isVirtual && !b.table.tableInfo.isVirtual) return 1;
            if (!a.table.tableInfo.isVirtual && b.table.tableInfo.isVirtual) return -1;
            return 0;
        });
        for (ResolvedTable table : tables) {
            StringBuilder tableCreateSql = new StringBuilder();
            if (table.table.tableInfo.isVirtual) {
                if (SqlGenerator.generateCreateViewSql(table, tableCreateSql, console))
                    return true;
            } else {
                if (SqlGenerator.generateCreateTableSql(table, tableCreateSql, console))
                    return true;
            }
            initSchemeAndTable.addStatement("jdbc.execute(\"\"\"\n$L\"\"\")", tableCreateSql);
        }

        MethodSpec onApplicationEvent = MethodSpec.methodBuilder("onApplicationEvent")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(
                                ApplicationReadyEvent.class, "event")
                        .addModifiers(Modifier.FINAL)
                        .build())
                .addStatement("logger.debug($S)", "Start init scheme and table")
                .addStatement("initSchemeAndTable()")
                .addStatement("logger.debug($S)", "Init scheme and table success")
                .build();

        TypeSpec typeSpec = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Component.class)
                .addSuperinterface(ParameterizedTypeName.get(
                        ApplicationListener.class,
                        ApplicationReadyEvent.class))
                .addField(logger)
                .addField(jdbc)
                .addMethod(constructor)
                .addMethod(initSchemeAndTable.build())
                .addMethod(onApplicationEvent)
                .build();

        try {
            JavaFile.builder(classPackage, typeSpec)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            console.printMessage(ERROR, "Could not write class: '" + classPackage + "." + className + "'");
            return true;
        }

        return false;
    }

    private boolean generateFile(TableData tableData) {
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
        Map<String, MethodInfo.Projection> projections = new LinkedHashMap<>();
        for (MethodInfo method : tableData.interfaceMethodInfo)
            if (method.returns.projection() != null)
                projections.putIfAbsent(method.returns.projection().type().getQualifiedName().toString(), method.returns.projection());
        Map<String, String> projectionMappers = projectionMapperFields(projections.values(), tableData);
        for (MethodInfo.Projection projection : projections.values()) {
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
        for (MethodInfo.Projection projection : projections.values())
            constructorBuilder.addStatement("$N = new $T<>($T.class, $L)",
                    projectionMappers.get(projection.type().getQualifiedName().toString()),
                    FastResultSetExtractor.class,
                    ClassName.get(projection.type()),
                    projection.columns().size());

        typeBuilder.addMethod(constructorBuilder.build());

        // Add repository method
        if (generateRepositoryMethods(tableData, typeBuilder, projectionMappers))
            return true;

        try {
            JavaFile.builder(tableInfo.classPackagePath, typeBuilder.build())
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            console.printMessage(ERROR, "Could not write class: '" + tableInfo.repoClassPath + "'. Error: " + e);
            return true;
        }
        return false;
    }


    // Projection qualified name -> extractor field name: simple name + "Mapper", suffixed when two projections or a dependency field would collide
    private static Map<String, String> projectionMapperFields(Collection<MethodInfo.Projection> projections, TableData tableData) {
        Set<String> taken = new HashSet<>(tableData.getDependencies().values());
        taken.add("jdbc");
        taken.add(TABLE_MAPPER);
        Map<String, String> fields = new LinkedHashMap<>();
        for (MethodInfo.Projection projection : projections) {
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

    private boolean generateRepositoryMethods(TableData tableData, TypeSpec.Builder typeBuilder, Map<String, String> projectionMappers) {
        for (MethodInfo method : tableData.interfaceMethodInfo) {
            boolean error = switch (method.operation) {
                case INSERT -> generateRepositoryInsertMethod(method, tableData, typeBuilder);
                case UPDATE -> generateRepositoryUpdateMethod(method, tableData, typeBuilder, false);
                case UPDATE_CHECKED -> generateRepositoryUpdateMethod(method, tableData, typeBuilder, true);
                case DELETE -> generateRepositoryDeleteMethod(method, tableData, typeBuilder);
                case COUNT -> generateRepositoryCountMethod(method, tableData, typeBuilder);
                case EXISTS -> generateRepositoryCheckMethod(method, tableData, typeBuilder);
                case SELECT_ROWS -> generateRepositorySearchMethod(method, tableData, typeBuilder);
                case SELECT_PROJECTION -> generateRepositoryProjectionMethod(method, tableData, typeBuilder,
                        projectionMappers.get(method.returns.projection().type().getQualifiedName().toString()));
                case SELECT_COLUMN ->generateRepositorySearchColumnMethod(method, tableData, typeBuilder);
            };
            if (error)
                return true;
        }
        return false;
    }


    private boolean generateRepositoryInsertMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        boolean returnInt = methodInfo.returnTypeMirror.getKind() == TypeKind.INT;
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        List<MethodParamInfo> infos = new ArrayList<>(methodInfo.params);

        CodeBlock idGenerator = JdbcCodeGenerator.addIdGenerator(tableData, infos);
        CodeBlock enumString = JdbcCodeGenerator.checkAndConvertEnumToStringArray(infos);

        JdbcCodeGenerator.QueryAndArgs values = JdbcCodeGenerator.getQueryAndArgs(infos, methodInfo, true, false, null, ",", false, tableData);

        if (methodInfo.batchInsert) {
            MethodParamInfo param = methodInfo.params.get(0);
            String typeStr = ((DeclaredType) param.parameter.asType()).asElement().getSimpleName().toString();

            methodBuilder.addStatement("$T<Object[]> batchValues = new $T<>($L_.size())",
                    List.class, ArrayList.class, param.paramName);
            methodBuilder.beginControlFlow("for ($L $L : $L_)", typeStr, param.paramName, param.paramName);
            methodBuilder.addCode(idGenerator);
            methodBuilder.addCode(enumString);
            methodBuilder.addStatement("batchValues.add(new Object[]{$L})", CodeBlock.join(values.args(), ", "));
            methodBuilder.endControlFlow();

            methodBuilder.addStatement("int[] result = jdbc.batchUpdate($S, batchValues)", "INSERT INTO " + quoteTable(tableInfo) + values.query());

            if (returnInt)
                methodBuilder.addStatement("return $T.stream(result).sum()", Arrays.class);
        } else {
            methodBuilder.addCode(idGenerator);
            methodBuilder.addCode(enumString);
            String sql = "INSERT INTO " + quoteTable(tableInfo) + values.query();

            if (returnInt && !methodInfo.returns.table())
                methodBuilder.addStatement("return ");

            if (values.args().isEmpty()) {
                methodBuilder.addStatement("jdbc.update($S)", sql);
            } else {
                methodBuilder.addStatement("jdbc.update($S, $L)", sql, CodeBlock.join(values.args(), ", "));
            }

            if (methodInfo.returns.table()) {
                JdbcCodeGenerator.QueryAndArgs returnValues = JdbcCodeGenerator.getQueryAndArgs(infos, null, false, true, null, ",", true, tableData);
                methodBuilder.addStatement("return new $T($L)", ClassName.bestGuess(tableInfo.className), CodeBlock.join(returnValues.args(), ", "));
            }
        }

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }


    private boolean generateRepositoryCheckMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

        String sql = "SELECT COUNT(*) FROM " + quoteTable(tableInfo) + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcQueryObject(methodBuilder, sql, queryWithArgs.args(), int.class, true);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    private boolean generateRepositoryCountMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

        String sql = "SELECT COUNT(*) FROM " + quoteTable(tableInfo) + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcQueryObject(methodBuilder, sql, queryWithArgs.args(), int.class, false);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    private boolean generateRepositorySearchColumnMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        StringBuilder columnQuery = new StringBuilder();
        List<CodeBlock> columnArgs = new ArrayList<>();
        MethodInfo.ReturnColumn returnColumn = methodInfo.returns.column();
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

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);
        List<CodeBlock> sqlArgs;
        if (!columnArgs.isEmpty()) {
            sqlArgs = new ArrayList<>(columnArgs);
            sqlArgs.addAll(queryWithArgs.args());
        } else
            sqlArgs = queryWithArgs.args();

        String sql = "SELECT " + columnQuery + " FROM " + quoteTable(tableInfo) + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, sqlArgs, null);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    // Same as a row search, but SELECTs only the projection's columns, in component order -- the extractor fills the constructor by position
    private boolean generateRepositoryProjectionMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder, String mapperField) {
        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

        StringBuilder columnQuery = new StringBuilder();
        for (ColumnInfo column : methodInfo.returns.projection().columns()) {
            if (!columnQuery.isEmpty()) columnQuery.append(',');
            columnQuery.append(quoteColumn(column.columnName));
        }

        String sql = "SELECT " + columnQuery + " FROM " + quoteTable(tableData.tableInfo) + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, queryWithArgs.args(), mapperField);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    private boolean generateRepositorySearchMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

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
        return false;
    }


    private boolean generateRepositoryDeleteMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

        String sql = "DELETE FROM " + quoteTable(tableInfo) + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcUpdate(methodBuilder, sql, queryWithArgs.args(), methodInfo.returnTypeMirror);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    private boolean generateRepositoryUpdateMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder, boolean checkSuccess) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        List<MethodParamInfo> whereColumns = new ArrayList<>();
        List<MethodParamInfo> updateColumns = new ArrayList<>();
        for (MethodParamInfo param : methodInfo.params) {
            if (param.columns == null) continue;
            if (param.where)
                whereColumns.add(param);
            else {
                if (!param.dataClass && param.columns.size() > 1) {
                    console.printMessage(ERROR, "Multiple column for value", param.parameter);
                    return true;
                }
                updateColumns.add(param);
            }
        }

        methodBuilder.addCode(JdbcCodeGenerator.checkAndConvertEnumToStringArray(methodInfo.params));

        JdbcCodeGenerator.QueryAndArgs update = JdbcCodeGenerator.updateQueryAndArgs(whereColumns, updateColumns, methodInfo, tableData);

        String sql = "UPDATE " + quoteTable(tableInfo) + update.query();

        if (methodInfo.returns.table()) {
            if (update.args().isEmpty())
                methodBuilder.beginControlFlow("if (jdbc.update($S) == 1)", sql);
            else
                methodBuilder.beginControlFlow("if (jdbc.update($S, $L) == 1)", sql, CodeBlock.join(update.args(), ", "));

            JdbcCodeGenerator.QueryAndArgs where = JdbcCodeGenerator.getQueryAndArgs(whereColumns, null, false, false, "WHERE ", " AND ", false, tableData);

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
        return false;
    }

    private boolean generateClassDependencies(TableInfo tableInfo, TableData tableData, TypeSpec.Builder typeBuilder, MethodSpec.Builder constructorBuilder) {
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
        return false;
    }


}
