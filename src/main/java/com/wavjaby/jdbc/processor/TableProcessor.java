package com.wavjaby.jdbc.processor;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.processor.model.*;
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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationMirror;
import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationValueClassElement;
import static javax.tools.Diagnostic.Kind.ERROR;


@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("com.wavjaby.jdbc.annotation.Table")
@SuppressWarnings("unused")
public class TableProcessor extends AbstractProcessor {
    private Messager console;
    private static Elements elementUtils;
    private Filer filer;

    @SuppressWarnings("unused")
    public TableProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
        this.console = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
    }

    public static Elements getElementUtils() {
        return elementUtils;
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

            boolean allSuccess = true;
            for (TableData tableData : tableDataMap.values())
                if (processTableData(tableData, tableDataMap)) allSuccess = false;
            if (!allSuccess)
                return false;

            for (TableData tableData : tableDataMap.values())
                if (generateFile(tableData)) allSuccess = false;
            if (!allSuccess)
                return false;

            Map<String, TableData> tableDependency = new LinkedHashMap<>();
            for (TableData data : tableDataMap.values()) {
                processTableDependency(data, tableDataMap, tableDependency);
            }

            if (generateInitFile(tableDataMap, tableDependency))
                return false;

            if (ProcessorUtil.copyUtilityClasses(processingEnv, console))
                return false;

            System.out.println("RepositoryTemplate process done " + tableDataMap.size());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void processTableDependency(TableData data, Map<String, TableData> tableDataMap, Map<String, TableData> dependency) {
        if (data.tableInfo.isVirtual)
            processTableDependency(data.getVirtualBaseTableData(), tableDataMap, dependency);
        for (ColumnInfo info : data.tableFields.values()) {
            ColumnInfo referencedColumn = info.getReferencedColumn();
            if (referencedColumn != null && !dependency.containsKey(info.referencedTableClassPath))
                processTableDependency(referencedColumn.tableData, tableDataMap, dependency);
        }
        dependency.put(data.tableInfo.classPath, data);
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

    private boolean processTableData(TableData tableData, Map<String, TableData> tableDataMap) {
        for (ColumnInfo column : tableData.tableFields.values()) {
            // Prepare referencedColumnInfo and referencedTableData
            if (column.processTableJoin(tableDataMap, tableData, console))
                return true;
        }

        if (tableData.tableInfo.isVirtual && tableData.processVirtualTable(tableData, tableDataMap, console))
            return true;

        return tableData.processTableJoin(tableData, tableDataMap, console);
    }

    private boolean generateInitFile(Map<String, TableData> tableDataMap, Map<String, TableData> tableDependency) {
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

        // Create table create SQL
        List<TableData> tables = new ArrayList<>(tableDependency.values());
        tables.sort((a, b) -> {
            if (a.tableInfo.isVirtual && !b.tableInfo.isVirtual) return 1;
            if (!a.tableInfo.isVirtual && b.tableInfo.isVirtual) return -1;
            return 0;
        });
        for (TableData tableData : tables) {
            StringBuilder tableCreateSql = new StringBuilder();
            if (tableData.tableInfo.isVirtual) {
                if (SqlGenerator.generateCreateViewSql(tableData, tableCreateSql, console))
                    return true;
            } else {
                if (SqlGenerator.generateCreateTableSql(tableData, tableCreateSql, console))
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
        typeBuilder.addField(FieldSpec.builder(tableMapperType, "tableMapper")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build());

        // Constructor
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(JdbcTemplate.class, "jdbc")
                .addStatement("this.jdbc = jdbc");

        // Add repository dependency
        generateClassDependencies(tableInfo, tableData, typeBuilder, constructorBuilder);

        // Finish constructor
        constructorBuilder.addStatement("tableMapper = new $T<>($T.class, $L)",
                FastResultSetExtractor.class,
                tableDataClass,
                tableData.tableColumns.size());

        typeBuilder.addMethod(constructorBuilder.build());

        // Add repository method
        if (generateRepositoryMethods(tableData, typeBuilder))
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


    private boolean generateRepositoryMethods(TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        for (MethodInfo method : tableData.interfaceMethodInfo) {

            // Explicit column SQL selection takes highest priority
            if (method.returns.column().columnSqlParams() != null) {
                if (generateRepositorySearchColumnMethod(method, tableData, typeBuilder))
                    return true;
                continue;
            }

            if (method.batchInsert) {
                if (generateRepositoryInsertMethod(method, tableData, typeBuilder))
                    return true;
                continue;
            }

            // Delete methods: only applicable for boolean/int/void returns.
            if (method.delete) {
                TypeKind kind = method.returnTypeMirror.getKind();
                if (kind == TypeKind.BOOLEAN || kind == TypeKind.INT || kind == TypeKind.VOID) {
                    if (generateRepositoryDeleteMethod(method, tableData, typeBuilder))
                        return true;
                    continue;
                }
                console.printMessage(ERROR, "Unrecognized return type for delete method: " + method.method, method.method);
                return true;
            }

            // Primitive return types
            if (method.returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
                TypeKind kind = primitiveReturnType.getKind();
                if (kind == TypeKind.BOOLEAN) {
                    if (method.modifyRow) {
                        // Update and check success
                        if (generateRepositoryUpdateMethod(method, tableData, typeBuilder, true))
                            return true;
                        continue;
                    } else {
                        // Check existence
                        if (generateRepositoryCheckMethod(method, tableData, typeBuilder))
                            return true;
                        continue;
                    }
                } else if (kind == TypeKind.INT) {
                    if (method.count) {
                        if (generateRepositoryCountMethod(method, tableData, typeBuilder))
                            return true;
                        continue;
                    }
                }

                console.printMessage(ERROR, "Unrecognized return type for " + method.returns.typeName() + " method: " + method.method, method.method);
                return true;
            }

            // Void return type
            if (method.returnTypeMirror instanceof NoType) {
                if (method.modifyRow) {
                    if (generateRepositoryUpdateMethod(method, tableData, typeBuilder, false))
                        return true;
                    continue;
                }
                else if (method.insertMethod) {
                    if (generateRepositoryInsertMethod(method, tableData, typeBuilder))
                        return true;
                    continue;
                }

                console.printMessage(ERROR, "Only insert, update method allow return void, method: " + method.method, method.method);
                return true;
            }

            // Declared and other return types
            if (method.returns.table()) {
                // Update function
                if (method.modifyRow) {
                    if (generateRepositoryUpdateMethod(method, tableData, typeBuilder, false))
                        return true;
                    continue;
                }
                // Insert function
                else if (method.insertMethod) {
                    if (generateRepositoryInsertMethod(method, tableData, typeBuilder))
                        return true;
                    continue;
                }
                // Query function
                else if (generateRepositorySearchMethod(method, tableData, typeBuilder))
                    return true;
                continue;
            }

            // Return a single column
            if (method.returns.column().column() != null) {
                if (generateRepositorySearchColumnMethod(method, tableData, typeBuilder))
                    return true;
                continue;
            }

            console.printMessage(ERROR, "Unrecognized repository method configuration: " + method.method, method.method);
            return true;
        }
        return false;
    }


    private boolean generateRepositoryInsertMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        if (methodInfo.returns.list()) {
            console.printMessage(ERROR, "Unsupported method return type: " + methodInfo.returnTypeMirror + ", for insert method", methodInfo.method);
            return true;
        }

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

            methodBuilder.addStatement("int[] result = jdbc.batchUpdate($S, batchValues)", "INSERT INTO " + tableInfo.quotedTableFullName + values.query());

            if (returnInt)
                methodBuilder.addStatement("return $T.stream(result).sum()", Arrays.class);
        } else {
            methodBuilder.addCode(idGenerator);
            methodBuilder.addCode(enumString);
            String sql = "INSERT INTO " + tableInfo.quotedTableFullName + values.query();

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

        String sql = "SELECT COUNT(*) FROM " + tableInfo.quotedTableFullName + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcQueryObject(methodBuilder, sql, queryWithArgs.args(), int.class, true);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    private boolean generateRepositoryCountMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

        String sql = "SELECT COUNT(*) FROM " + tableInfo.quotedTableFullName + queryWithArgs.query();

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
            columnQuery.append(returnColumn.column().quotedColumnName);
        }

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);
        List<CodeBlock> sqlArgs;
        if (!columnArgs.isEmpty()) {
            sqlArgs = new ArrayList<>(columnArgs);
            sqlArgs.addAll(queryWithArgs.args());
        } else
            sqlArgs = queryWithArgs.args();

        String sql = "SELECT " + columnQuery + " FROM " + tableInfo.quotedTableFullName + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, sqlArgs, false);

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
            columnQuery.append(column.quotedColumnName);
        }

        String sql = "SELECT " + columnQuery + " FROM " + tableInfo.quotedTableFullName + queryWithArgs.query() + SqlGenerator.sqlResultModifier(methodInfo);

        JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, sql, queryWithArgs.args(), true);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }


    private boolean generateRepositoryDeleteMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder) {
        TableInfo tableInfo = tableData.tableInfo;

        MethodSpec.Builder methodBuilder = JdbcCodeGenerator.getClassDefinition(methodInfo);

        JdbcCodeGenerator.QueryAndArgs queryWithArgs = JdbcCodeGenerator.getQueryAndArgs(methodInfo.params, methodInfo, false, false, "WHERE ", " AND ", false, tableData);

        String sql = "DELETE FROM " + tableInfo.quotedTableFullName + queryWithArgs.query();

        JdbcCodeGenerator.buildJdbcUpdate(methodBuilder, sql, queryWithArgs.args(), methodInfo.returnTypeMirror);

        typeBuilder.addMethod(methodBuilder.build());
        return false;
    }

    private boolean generateRepositoryUpdateMethod(MethodInfo methodInfo, TableData tableData, TypeSpec.Builder typeBuilder, boolean checkSuccess) {
        if (methodInfo.returns.list()) {
            console.printMessage(ERROR, "Unsupported method return type: " + methodInfo.returnTypeMirror + ", for update method", methodInfo.method);
            return true;
        }

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

        String sql = "UPDATE " + tableInfo.quotedTableFullName + update.query();

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
                columnQuery.append(column.quotedColumnName);
            }

            String selectSql = "SELECT " + columnQuery + " FROM " + tableInfo.quotedTableFullName + where.query();

            JdbcCodeGenerator.buildJdbcQueryReturn(methodBuilder, methodInfo, selectSql, where.args(), true);

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
