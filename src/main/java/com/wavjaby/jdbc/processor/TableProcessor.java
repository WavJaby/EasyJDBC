package com.wavjaby.jdbc.processor;

import com.google.auto.service.AutoService;
import com.wavjaby.jdbc.Table;
import com.wavjaby.jdbc.processor.model.*;
import com.wavjaby.persistence.Column;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationMirror;
import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationValueClassElement;
import static com.wavjaby.jdbc.processor.util.ProcessorUtil.getResourceAsString;
import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static javax.tools.Diagnostic.Kind.ERROR;


@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes("com.wavjaby.jdbc.Table")
@SuppressWarnings("unused")
public class TableProcessor extends AbstractProcessor {
    private final String repoTemplate, initTemplate;
    private Messager console;
    private static Elements elementUtils;

    @SuppressWarnings("unused")
    public TableProcessor() {
        // Read template
        String repoTemplate;
        try (InputStream inputStream = getClass().getResourceAsStream("/RepositoryTemplate.java")) {
            assert inputStream != null;
            repoTemplate = getResourceAsString(inputStream);
        } catch (IOException | AssertionError ignored) {
            processingEnv.getMessager().printMessage(ERROR, "Could not read template file");
            repoTemplate = null;
        }
        this.repoTemplate = repoTemplate;


        String initTemplate;
        try (InputStream inputStream = getClass().getResourceAsStream("/RepositoryInitTemplate.java")) {
            assert inputStream != null;
            initTemplate = getResourceAsString(inputStream);
        } catch (IOException | AssertionError ignored) {
            processingEnv.getMessager().printMessage(ERROR, "Could not read template file");
            initTemplate = null;
        }
        this.initTemplate = initTemplate;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
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
            this.console = processingEnv.getMessager();
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

            if (copyUtilityClasses())
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
            TableData refrenceTableData = info.getReferencedTableData();
            if (refrenceTableData != null && !dependency.containsKey(info.referencedTableClassPath))
                processTableDependency(refrenceTableData, tableDataMap, dependency);
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
        if (tableData.tableInfo.isVirtual && tableData.processVirtualTable(tableData, tableDataMap, console))
            return true;

        return tableData.processTableJoin(tableData, tableDataMap, console);
    }

    private boolean generateInitFile(Map<String, TableData> tableDataMap, Map<String, TableData> tableDependency) {
        String classPackage = "com.wavjaby.jdbc.util";
        String className = "RepositoryInit";
        String classPath = classPackage + '.' + className;
        String template = this.initTemplate;
        template = template.replace("%CLASS_PACKAGE_NAME%", "package " + classPackage + ';');
        template = template.replace("%CLASS_NAME%", className);

        Set<String> schemaList = new HashSet<>();
        for (TableData tableData : tableDataMap.values()) {
            if (tableData.tableInfo.schema == null) continue;
            schemaList.add(tableData.tableInfo.schema);
        }

        StringBuilder builder = new StringBuilder();
        // Create schema init code
        if (!schemaList.isEmpty()) {
            builder.append("jdbc.execute(");
            boolean first = true;
            for (String schema : schemaList) {
                if (!first) builder.append("+");
                first = false;

                builder.append("\n        \"create schema if not exists ").append(schema).append(";\"");
            }
            builder.append("\n        );\n");
        }

        // Create table create SQL
        for (TableData tableData : tableDependency.values()) {
            StringBuilder tableCreateSql = new StringBuilder();
            if (tableData.tableInfo.isVirtual) {
                if (generateCreateViewSql(tableData, tableCreateSql))
                    return true;
            } else {
                if (generateCreateTableSql(tableData, tableCreateSql))
                    return true;
            }
            builder.append("\n        jdbc.execute(").append(tableCreateSql).append(");\n");
        }

        builder.append("/*\n");
        for (TableData tableData : tableDependency.values()) {
            TableInfo tableInfo = tableData.tableInfo;
            String tableCreateSql = '"' + Objects.requireNonNullElse(tableInfo.schema, "public") + "\".\"" + tableInfo.name + '"';
            builder.append(tableCreateSql).append("\n");
        }
        builder.append("*/\n");

        template = template.replace("%INIT_TABLE%", builder);

        try (Writer out = processingEnv.getFiler().createSourceFile(classPath).openWriter()) {
            out.write(template);
        } catch (IOException e) {
            console.printMessage(ERROR, "Could not write class: '" + classPath + "'");
            return true;
        }

        return false;
    }

    private boolean generateFile(TableData tableData) {
        TableInfo tableInfo = tableData.tableInfo;

        // Create class
        String template = this.repoTemplate;
        template = template.replace("%CLASS_PACKAGE_NAME%", tableInfo.repoPackagePath);
        template = template.replace("%REPOSITORY_IMPL_NAME%", tableInfo.repoClassName);
        template = template.replace("%INTERFACE_CLASS_PATH%", tableInfo.repoIntClassPath);
        template = template.replace("%TABLE_DATA_CLASS%", tableInfo.className);
        template = template.replace("%TABLE_NAME%", tableInfo.name);
        template = template.replace("%SQL_TABLE_COLUMN_COUNT%", String.valueOf(tableData.tableColumnNames.size()));

        // Add repository method
        StringBuilder repoMethodBuilder = new StringBuilder();
        if (generateRepositoryMethods(tableData, repoMethodBuilder))
            return true;

        // Add repository dependency
        template = generateClassDependencies(tableInfo, tableData, template);
        template = template.replace("%REPOSITORY_METHODS%", repoMethodBuilder.toString());

        try (Writer out = processingEnv.getFiler().createSourceFile(tableInfo.repoClassPath).openWriter()) {
            out.write(template);
        } catch (IOException e) {
            console.printMessage(ERROR, "Could not write class: '" + tableInfo.repoClassPath + "'");
            return true;
        }
        return false;
    }

    private boolean generateCreateViewSql(TableData tableData, StringBuilder builder) {
        TableInfo tableInfo = tableData.tableInfo;
        TableData base = tableData.getVirtualBaseTableData();
        String baseTableShortName = "B";

        // Find table reference
        List<TableData> referencedTable = new ArrayList<>();
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (columnInfo.joinColumn != null) {
                referencedTable.add(columnInfo.getReferencedTableData());
            }
        }

        builder.append("\"create or replace view ");
        if (tableInfo.schema != null) builder.append(tableInfo.schema).append('.');
        builder.append(tableInfo.name).append(" as select ");
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            String fieldName = entry.getKey();
            ColumnInfo columnInfo = entry.getValue();

            ColumnInfo targetColumn = base.tableFields.get(fieldName);
            String targetTableName = base.tableInfo.name;
            String targetTableSchema = base.tableInfo.schema;
            boolean baseTableColumn = targetColumn != null;
            // Find field from other table
            if (targetColumn == null)
                for (TableData table : referencedTable) {
                    targetColumn = table.tableFields.get(fieldName);
                    if (targetColumn != null) {
                        targetTableName = table.tableInfo.name;
                        targetTableSchema = table.tableInfo.schema;
                        break;
                    }
                }
            if (targetColumn == null) {
                console.printMessage(ERROR, "Field '" + fieldName + "' not found in referenced table", columnInfo.field);
                return true;
            }


            if (!first) builder.append(',');
            first = false;

            if (baseTableColumn)
                builder.append(baseTableShortName).append('.').append(columnInfo.columnName);
            else {
                if (targetTableSchema != null)
                    builder.append(targetTableSchema).append(".");
                builder.append(targetTableName).append('.').append(targetColumn.columnName);
            }
        }
        builder.append("\"+\n");
        builder.append("        \" from ");
        if (base.tableInfo.schema != null) builder.append(base.tableInfo.schema).append(".");
        builder.append(base.tableInfo.name).append(" ").append(baseTableShortName);

        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            String fieldName = entry.getKey();
            ColumnInfo columnInfo = entry.getValue();


            if (columnInfo.joinColumn == null)
                continue;

            TableInfo targetTableInfo = columnInfo.getReferencedTableData().tableInfo;
            String targetTableFullName = targetTableInfo.name;
            if (targetTableInfo.schema != null)
                targetTableFullName = targetTableInfo.schema + "." + targetTableFullName;

            String targetColumnName = columnInfo.getReferencedColumnInfo().columnName;
            builder.append("\"+\n");
            builder.append("        \" join ").append(targetTableFullName).append(" on ")
                    .append(baseTableShortName).append('.').append(columnInfo.columnName).append('=')
                    .append(targetTableFullName).append('.').append(targetColumnName);
        }
        builder.append("\"");

        return false;
    }

    private boolean generateCreateTableSql(TableData tableData, StringBuilder builder) {
        TableInfo tableInfo = tableData.tableInfo;
        builder.append("\"create table if not exists ");
        if (tableInfo.schema != null) builder.append(tableInfo.schema).append('.');
        builder.append(tableInfo.name).append("(");
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (!first) builder.append(',');
            builder.append("\"+\n");
            first = false;

            // Create field
            boolean columnDef = columnInfo.column != null;

            // column name
            builder.append("        \"");
            quoteColumnName(builder, columnInfo.columnName);

            // If column define by user
            if (columnDef && !columnInfo.column.columnDefinition().isEmpty()) {
                builder.append(' ').append(columnInfo.column.columnDefinition());
            }
            // Auto create column define
            else if (generateColumnDefinition(columnInfo, tableInfo, builder)) {
                console.printMessage(ERROR, "Could not generate column definition for column: " + columnInfo.columnName, columnInfo.field);
                return true;
            }

            // Apply default value
            if (columnInfo.defaultValue != null) {
                builder.append(" default ");
                if (columnInfo.isString)
                    builder.append('\'').append(columnInfo.defaultValue).append('\'');
                else
                    builder.append(columnInfo.defaultValue);
            }
            if (!columnInfo.nullable) builder.append(" not null");

        }
        if (!tableData.primaryKey.isEmpty()) {
            builder.append(",\"+\n");
            builder.append("        \"constraint ").append(tableInfo.name).append("_PK");
            builder.append(" primary key(");
            for (int i = 0; i < tableData.primaryKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(tableData.primaryKey.get(i).columnName);
            }
            builder.append(")");
        }
        for (List<ColumnInfo> uniqueKey : tableData.uniqueKeyList) {
            builder.append(",\"+\n");
            builder.append("        \"constraint").append(tableInfo.getUniqueKey(uniqueKey)).append(" unique (");
            for (int i = 0; i < uniqueKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(uniqueKey.get(i).columnName);
            }
            builder.append(")");
        }

        for (TableData.ForeignKeyGroup foreignKeyGroup : tableData.foreignKeyList) {
            String foreignKeyName = tableInfo.name + '_' + String.join("__", foreignKeyGroup.sourceColumns()) + "_FK";

            builder.append(",\"+\n");
            builder.append("        \"constraint ").append(foreignKeyName).append(" foreign key (")
                    .append(String.join(",", foreignKeyGroup.sourceColumns())).append(") references ");
            // If main table have schema, referenced table have to specify as well
            if (tableInfo.schema != null || foreignKeyGroup.referencedTable().schema != null) {
                String referencedTableSchema = foreignKeyGroup.referencedTable().schema;
                if (referencedTableSchema == null)
                    referencedTableSchema = convertPropertyNameToUnderscoreName("PUBLIC");
                builder.append(referencedTableSchema).append(".");
            }
            builder.append(foreignKeyGroup.referencedTable().name).append("(").append(String.join(",", foreignKeyGroup.referencedColumns())).append(")");
        }

        builder.append(");\"");

        return false;
    }

    private boolean generateRepositoryMethods(TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        for (MethodInfo method : tableData.interfaceMethodInfo) {

            // Explicit column SQL selection takes highest priority
            if (!method.returnColumnSqlParams.isEmpty()) {
                if (generateRepositorySearchColumnMethod(method, tableData, repoMethodBuilder))
                    return true;
                continue;
            }

            if (method.batchInsert) {
                if (generateRepositoryInsertMethod(method, tableData, repoMethodBuilder))
                    return true;
                continue;
            }

            // Delete methods: only applicable for boolean/int/void returns.
            if (method.delete) {
                TypeKind kind = method.returnTypeMirror.getKind();
                if (kind == TypeKind.BOOLEAN || kind == TypeKind.INT || kind == TypeKind.VOID) {
                    if (generateRepositoryDeleteMethod(method, tableData, repoMethodBuilder))
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
                        if (generateRepositoryUpdateMethod(method, tableData, repoMethodBuilder, true))
                            return true;
                        continue;
                    } else {
                        // Check existence
                        if (generateRepositoryCheckMethod(method, tableData, repoMethodBuilder))
                            return true;
                        continue;
                    }
                } else if (kind == TypeKind.INT) {
                    if (method.count) {
                        if (generateRepositoryCountMethod(method, tableData, repoMethodBuilder))
                            return true;
                        continue;
                    }
                }

                console.printMessage(ERROR, "Unrecognized return type for " + method.returnTypeName + " method: " + method.method, method.method);
                return true;
            }

            // Void return type
            if (method.returnTypeMirror instanceof NoType) {
                if (method.modifyRow) {
                    if (generateRepositoryUpdateMethod(method, tableData, repoMethodBuilder, false))
                        return true;
                    continue;
                }

                console.printMessage(ERROR, "Only update method allow return void, method: " + method.method, method.method);
                return true;
            }

            // Declared and other return types
            if (method.returnSelfTable) {
                // Update function
                if (method.modifyRow) {
                    if (generateRepositoryUpdateMethod(method, tableData, repoMethodBuilder, false))
                        return true;
                    continue;
                }
                // Insert function
                else if (method.insertMethod) {
                    if (generateRepositoryInsertMethod(method, tableData, repoMethodBuilder))
                        return true;
                    continue;
                }
                // Query function
                else if (generateRepositorySearchMethod(method, tableData, repoMethodBuilder))
                    return true;
                continue;
            }

            // Return a single column
            if (method.returnColumn != null) {
                if (generateRepositorySearchColumnMethod(method, tableData, repoMethodBuilder))
                    return true;
                continue;
            }

            console.printMessage(ERROR, "Unrecognized repository method configuration: " + method.method, method.method);
            return true;
        }
        return false;
    }

    /**
     * Constructs SQL query fragments and corresponding parameter arguments based on method parameters
     * and additional query constraints.
     *
     * @param params           List of method parameter information containing column mappings and metadata
     * @param methodInfo       Method metadata containing query SQL (can be null)
     * @param insert           true if generating INSERT clause for INSERT operation, false for UPDATE or WHERE clause
     * @param update           true if generating SET clause for UPDATE operation, false for WHERE clause
     * @param prefix           SQL prefix to prepend (e.g., " WHERE ", " SET "), can be null
     * @param conjunction      SQL conjunction operator between parameters (e.g., " AND ", ",")
     * @param tableConstructor true if formatting arguments for table constructor, false for prepared statement
     * @param tableData        Table metadata used for dependency management and column information
     * @return Array containing two StringBuilder objects: [0] = query fragment, [1] = arguments list
     */
    private StringBuilder[] getQueryAndArgs(List<MethodParamInfo> params, MethodInfo methodInfo, boolean insert, boolean update, String prefix, String conjunction, boolean tableConstructor, TableData tableData) {
        StringBuilder queryBuilder = new StringBuilder();
        StringBuilder argsBuilder = new StringBuilder();

        if (!tableConstructor && !params.isEmpty() || methodInfo != null && methodInfo.querySql != null) {
            if (prefix != null) queryBuilder.append(prefix);
        }
        if (!tableConstructor && !params.isEmpty()) {
            argsBuilder.append(',');
        }

        if (insert)
            queryBuilder.append("(");

        int tempVarCount = 0;

        int conditionCount = -1;
        for (MethodParamInfo param : params) {
            if (param.columns.isEmpty()) continue;
            if (++conditionCount != 0) queryBuilder.append(conjunction);

            // Query with multiple columns
            if (!insert && !update && param.columns.size() > 1)
                queryBuilder.append("(");
            int j = -1;
            for (ColumnInfo column : param.columns) {
                if (param.dataClass && column.idGenerator != null)
                    continue;

                if (insert || update) {
                    if (++j != 0) queryBuilder.append(',');
                } else {
                    if (++j != 0) queryBuilder.append(" or ");
                }

                // Query where with ignore case
                if (!update && param.ignoreCase) {
                    queryBuilder.append("LOWER(");
                    quoteColumnName(queryBuilder, column.columnName);
                    queryBuilder.append(") ").append(param.whereOperation).append(" LOWER(?)");
                } else if (insert)
                    quoteColumnName(queryBuilder, column.columnName);
//                else
//                    quoteColumnName(queryBuilder, column.columnName).append(param.whereOperation).append('?');
                else {
                    if (!update && column.nullable) {
                        quoteColumnName(queryBuilder, column.columnName);
                        queryBuilder.append(" IS NOT DISTINCT FROM ?");
                    } else {
                        quoteColumnName(queryBuilder, column.columnName).append(param.whereOperation).append('?');
                    }
                }

                String argName = param.paramName;
                // Get field if using data class
                if (param.dataClass) {
                    argName += '.' + column.field.getSimpleName().toString();
                    if (param.isRecord) argName += "()";
                }

                if (conditionCount + j != 0) argsBuilder.append(',');
                if (tableConstructor) {
                    argsBuilder.append(argName);
                } else {
                    if (column.isArray) {
                        // If enum array exist
                        if (column.isEnum) {
                            argName = "var" + tempVarCount;
                            tempVarCount++;
                        }

                        tableData.addDependency("org.springframework.jdbc.core.SqlParameterValue", null);
                        tableData.addDependency("static java.sql.Types.ARRAY", null);
                        argsBuilder.append("new SqlParameterValue(ARRAY,").append(argName).append(")");
                    } else if (column.isEnum) {
                        if (column.nullable)
                            argsBuilder.append(argName).append("==null?null:");
                        argsBuilder.append(argName).append(".name()");
                    } else {
                        argsBuilder.append(argName);
                    }
                }
            }
            if (!insert && !update && param.columns.size() > 1)
                queryBuilder.append(")");
        }
        if (insert)
            queryBuilder.append(')');

        // Appends query SQL and parameters if present
        if (methodInfo != null && methodInfo.querySql != null) {
            if (conditionCount > -1)
                queryBuilder.append(' ').append(methodInfo.querySql.conjunction()).append(' ');

            for (QueryParamInfo sqlParam : methodInfo.querySqlParams) {
                queryBuilder.append(sqlParam.sqlPart);
                if (sqlParam.paramName == null)
                    continue;
                queryBuilder.append('?');

                MethodParamInfo methodParam = sqlParam.getMethodParamInfo();

                if (++conditionCount != 0)
                    argsBuilder.append(',');
                if (methodParam != null && methodParam.parameter.asType() instanceof ArrayType) {
                    tableData.addDependency("org.springframework.jdbc.core.SqlParameterValue", null);
                    tableData.addDependency("static java.sql.Types.ARRAY", null);
                    argsBuilder.append("new SqlParameterValue(ARRAY,").append(sqlParam.paramName).append(")");
                } else {
                    argsBuilder.append(sqlParam.paramName);
                }

            }
        }
        return new StringBuilder[]{queryBuilder, argsBuilder};
    }

    private StringBuilder[] updateQueryAndArgs(List<MethodParamInfo> whereColumns, List<MethodParamInfo> updateColumns, MethodInfo methodInfo, TableData tableData) {
        StringBuilder[] where = getQueryAndArgs(whereColumns, methodInfo, false, false, " where ", " and ", false, tableData);
        StringBuilder[] values = getQueryAndArgs(updateColumns, null, false, true, " set ", ",", false, tableData);

        values[0].append(where[0]);
        values[1].append(where[1]);

        return new StringBuilder[]{values[0], values[1]};
    }

    private StringBuilder getClassDefinition(MethodInfo methodInfo) {
        return getClassDefinition(methodInfo.returnTypeName, methodInfo);
    }

    private StringBuilder getClassDefinition(String returnType, MethodInfo methodInfo) {

        // Build method definition
        StringBuilder builder = new StringBuilder();
        builder.append("\n    public ");

        if (methodInfo.returnList) builder.append("List<").append(methodInfo.getReturnListType()).append(">");
        else builder.append(returnType);

        builder.append(' ').append(methodInfo.methodName).append('(');
        // Add method param
        for (int i = 0; i < methodInfo.params.size(); ++i) {
            if (i != 0) builder.append(", ");
            MethodParamInfo param = methodInfo.params.get(i);
            String typeStr = param.dataClass
                    ? ((DeclaredType) param.parameter.asType()).asElement().getSimpleName().toString()
                    : param.paramTypeName;

            // Add param to builder
            if (methodInfo.batchInsert)
                builder.append("List<").append(typeStr).append('>');
            else
                builder.append(typeStr);

            builder.append(' ').append(param.paramName);

            // Modify the param name to `NAME_`
            if (methodInfo.batchInsert)
                builder.append('_');
        }
        builder.append(") {\n");
        return builder;
    }

    private boolean generateRepositoryInsertMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        if (methodInfo.returnList) {
            console.printMessage(ERROR, "Unsupported method return type: " + methodInfo.returnTypeMirror + ", for insert method", methodInfo.method);
            return true;
        }

        boolean returnInt = methodInfo.returnTypeMirror.getKind() == TypeKind.INT;

        TableInfo tableInfo = tableData.tableInfo;
//        List<? extends VariableElement> parameters = methodInfo.method.getParameters();
        StringBuilder methodDef = getClassDefinition(methodInfo);
        repoMethodBuilder.append(methodDef);

        List<MethodParamInfo> infos = new ArrayList<>(methodInfo.params);
        int i = -1;
        // Create Ids
        for (ColumnInfo info : tableData.tableFields.values()) {
            ++i;
            if (info.idGenerator == null) continue;
            String generator = tableData.getDependencyFieldName(info.idGenerator.toString());
            repoMethodBuilder.append("        ").append("long id").append(i).append(" = ").append(generator).append(".nextId();\n");
            infos.add(i, new MethodParamInfo(null, Collections.singletonList(info), "long", "id" + i, false, null, false));
        }

        checkAndConvertEnumToStringArray(repoMethodBuilder, infos);

        StringBuilder[] values = getQueryAndArgs(infos, null, true, false, null, ",", false, tableData);

        // Append variable values
        values[0].append(" values (");
        boolean first = true;
        for (MethodParamInfo info : methodInfo.params) {
            for (ColumnInfo column : info.columns) {
                if (!first) values[0].append(',');
                values[0].append('?');
                first = false;
            }
        }
        values[0].append(')');


        if (methodInfo.batchInsert) {
            tableData.addDependency("java.util.ArrayList", null);
            tableData.addDependency("java.util.Arrays", null);
            MethodParamInfo param = methodInfo.params.get(0);
            String typeStr = ((DeclaredType) param.parameter.asType()).asElement().getSimpleName().toString();
            repoMethodBuilder.append("        List<Object[]> batchValues = new ArrayList<>(").append(param.paramName).append("_.size());\n");
            repoMethodBuilder.append("        for (").append(typeStr).append(' ').append(param.paramName).append(":").append(param.paramName).append("_){\n");
            repoMethodBuilder.append("            batchValues.add(new Object[]{").append(values[1], 1, values[1].length()).append("});\n");
            repoMethodBuilder.append("        }\n");

            repoMethodBuilder.append("        int[] result = jdbc.batchUpdate(\"insert into ").append(tableInfo.fullname)
                    .append(values[0]).append("\",batchValues);\n");

            if (returnInt) {
                // Sum result count
                repoMethodBuilder.append("        return Arrays.stream(result).sum();\n");
            }
        } else {
            repoMethodBuilder.append("        ");
            if (returnInt) repoMethodBuilder.append("return ");
            repoMethodBuilder.append("jdbc.update(\"insert into ").append(tableInfo.fullname)
                    .append(values[0]).append('"').append(values[1]).append(");\n");
        }

        // Create return
        if (methodInfo.returnSelfTable) {
            values = getQueryAndArgs(infos, null, false, true, null, ",", true, tableData);
            repoMethodBuilder.append("        return new ").append(tableInfo.className).append('(')
                    .append(values[1]).append(");\n");
        }

        repoMethodBuilder.append("    }\n");
        return false;
    }

    private static void checkAndConvertEnumToStringArray(StringBuilder repoMethodBuilder, List<MethodParamInfo> infos) {
        // Create temp var if enum array exist
        int tempVarCount = 0;
        for (MethodParamInfo param : infos) {
            for (ColumnInfo column : param.columns) {
                if (param.dataClass && column.idGenerator != null)
                    continue;

                String argName = param.paramName;
                // Get field if using data class
                if (param.dataClass) {
                    argName += '.' + column.field.getSimpleName().toString();
                    if (param.isRecord) argName += "()";
                }

                if (column.isArray && column.isEnum) {
                    repoMethodBuilder.append("        ").append("String[] var").append(tempVarCount).append(" = new String[").append(argName).append(".length];\n");
                    repoMethodBuilder.append("        for (int i = 0; i < ").append(argName).append(".length; i++)\n");
                    repoMethodBuilder.append("            var").append(tempVarCount).append("[i] = ").append(argName).append("[i].name();\n");

                    tempVarCount++;
                }
            }
        }
    }

    private boolean generateRepositoryCheckMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        List<? extends VariableElement> parameters = methodInfo.method.getParameters();
        StringBuilder methodDef = getClassDefinition("boolean", methodInfo);
        repoMethodBuilder.append(methodDef);


        // Count query result
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo, false, false, " where ", " and ", false, tableData);
        repoMethodBuilder.append("        return jdbc.queryForObject(\"select count(*) from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append("\"")
                .append(",Integer.class").append(queryWithArgs[1]).append(") > 0;\n");
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositoryCountMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        List<? extends VariableElement> parameters = methodInfo.method.getParameters();
        StringBuilder methodDef = getClassDefinition("int", methodInfo);
        repoMethodBuilder.append(methodDef);


        // Count query result
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo, false, false, " where ", " and ", false, tableData);
        repoMethodBuilder.append("        return jdbc.queryForObject(\"select count(*) from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append("\"")
                .append(",Integer.class").append(queryWithArgs[1]).append(");\n");
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositorySearchColumnMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        String returnTypeStr = methodInfo.returnTypeName;
        StringBuilder methodDef = getClassDefinition(methodInfo);
        repoMethodBuilder.append(methodDef);

        StringBuilder columnQuery = new StringBuilder();
        StringBuilder columnArgs = new StringBuilder();
        if (!methodInfo.returnColumnSqlParams.isEmpty()) {
            boolean first = true;
            for (QueryParamInfo param : methodInfo.returnColumnSqlParams) {
                columnQuery.append(param.sqlPart);
                if (param.paramName != null) {
                    columnQuery.append('?');
                    if (!first) columnArgs.append(',');
                    first = false;

                    MethodParamInfo methodParam = param.getMethodParamInfo();
                    if (methodParam != null && methodParam.parameter.asType() instanceof ArrayType) {
                        tableData.addDependency("org.springframework.jdbc.core.SqlParameterValue", null);
                        tableData.addDependency("static java.sql.Types.ARRAY", null);
                        columnArgs.append("new SqlParameterValue(ARRAY,").append(param.paramName).append(")");
                    } else {
                        columnArgs.append(param.paramName);
                    }
                }
            }
        } else {
            quoteColumnName(columnQuery, methodInfo.returnColumn.columnName);
        }

        // Build method body, SQL query part
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo, false, false, " where ", " and ", false, tableData);

        // Add extra args
        if (!columnArgs.isEmpty()) {
            if (!queryWithArgs[1].isEmpty()) queryWithArgs[1].append(',');
            queryWithArgs[1].append(columnArgs);
        }


        if (methodInfo.returnList)
            repoMethodBuilder.append("        return");
        else
            repoMethodBuilder.append("        List<").append(methodInfo.getReturnListType()).append("> result =");

        repoMethodBuilder.append(" jdbc.queryForList(\"select ")
                .append(columnQuery).append(" from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append(sqlResultModifier(methodInfo)).append("\"")
                .append(',').append(returnTypeStr).append(".class").append(queryWithArgs[1]).append(");\n");

        if (!methodInfo.returnList)
            repoMethodBuilder.append("        return result.isEmpty() ? null : result.get(0);\n");
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositorySearchMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        String returnTypeStr = tableInfo.className;
        StringBuilder methodDef = getClassDefinition(methodInfo);
        repoMethodBuilder.append(methodDef);

        // Build method body, SQL query part
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo, false, false, " where ", " and ", false, tableData);
        if (methodInfo.returnList)
            repoMethodBuilder.append("        return");
        else
            repoMethodBuilder.append("        List<").append(methodInfo.getReturnListType()).append("> result =");

        StringBuilder columnQuery = new StringBuilder();
        for (String name : tableData.tableColumnNames) {
            if (!columnQuery.isEmpty()) columnQuery.append(',');
            quoteColumnName(columnQuery, name);
        }

        repoMethodBuilder.append(" jdbc.query(\"select ").append(columnQuery).append(" from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append(sqlResultModifier(methodInfo)).append("\"")
                .append(",tableMapper").append(queryWithArgs[1]).append(");\n");

        if (!methodInfo.returnList)
            repoMethodBuilder.append("        return result.isEmpty() ? null : result.get(0);\n");
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private StringBuilder sqlResultModifier(MethodInfo methodInfo) {
        StringBuilder builder = new StringBuilder();
        if (methodInfo.orderByColumns != null) {
            builder.append(" ORDER BY ");
            for (int i = 0; i < methodInfo.orderByColumns.length; i++) {
                ColumnInfo column = methodInfo.orderByColumns[i];
                builder.append(column.columnName).append(" ").append(methodInfo.orderBy[i].direction());
                if (i < methodInfo.orderByColumns.length - 1)
                    builder.append(", ");
            }
        }
        if (methodInfo.limit != null)
            builder.append(" LIMIT ").append(methodInfo.limit);
        return builder;
    }

    private boolean generateRepositoryDeleteMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        TypeKind returnType = methodInfo.returnTypeMirror.getKind();

        String returnTypeStr;
        if (returnType == TypeKind.BOOLEAN) {
            returnTypeStr = "boolean";
        } else if (returnType == TypeKind.INT) {
            returnTypeStr = "int";
        } else if (returnType == TypeKind.VOID) {
            returnTypeStr = "void";
        } else {
            console.printMessage(ERROR, "Unsupported method return type: " + methodInfo.returnTypeMirror + ", for delete method", methodInfo.method);
            return true;
        }


        List<? extends VariableElement> parameters = methodInfo.method.getParameters();
        StringBuilder methodDef = getClassDefinition(returnTypeStr, methodInfo);
        repoMethodBuilder.append(methodDef);

        // Build method body, SQL query part
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo, false, false, " where ", " and ", false, tableData);
        if (returnType != TypeKind.VOID) repoMethodBuilder.append("        return");
        else repoMethodBuilder.append("        ");

        repoMethodBuilder.append(" jdbc.update(\"delete from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append("\"").append(queryWithArgs[1]).append(")")
                .append(returnType == TypeKind.BOOLEAN ? " > 0;\n" : ";\n");

        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositoryUpdateMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder, boolean checkSuccess) {
        if (methodInfo.returnList) {
            console.printMessage(ERROR, "Unsupported method return type: " + methodInfo.returnTypeMirror + ", for update method", methodInfo.method);
            return true;
        }

        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        repoMethodBuilder.append(getClassDefinition(methodInfo));

        // Split param type
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
                if (param.dataClass)
                    tableData.addDependency(param.parameter.asType().toString(), null);
                updateColumns.add(param);
            }
        }
//        // Use value from record class
//        if (methodInfo.selfTableParameter != null) {
//            for (ColumnInfo info : tableData.tableFields.values()) {
//                if (info.isPrimaryKey || info.column != null && info.column.unique())
//                    continue;
//                updateColumns.add(new MethodParamInfo(methodInfo.selfTableParameter, info));
//            }
//        }

        checkAndConvertEnumToStringArray(repoMethodBuilder, methodInfo.params);

        // Build method body, SQL query part
        StringBuilder[] update = updateQueryAndArgs(whereColumns, updateColumns, methodInfo, tableData);
        if (methodInfo.returnSelfTable) {
            // Update query value if it will be modified
//            for (int i = 0; i < whereColumns.size(); i++) {
//                MethodParamInfo where = whereColumns.get(i);
//                MethodParamInfo modified = null;
//                for (ColumnInfo whereColumn : where.columns) {
//                    // Find column is modified
//                    for (MethodParamInfo value : updateColumns) {
//                        for (ColumnInfo updatedColumn : value.columns) {
//                            if (updatedColumn.columnName.equals(whereColumn.columnName)) {
//                                modified = value;
//                                break;
//                            }
//                        }
//                        if (modified != null)
//                            break;
//                    }
//                    if (modified != null)
//                        whereColumns.set(i, modified);
//                }
//            }

            // Update data
            repoMethodBuilder.append("        if (jdbc.update(\"update ").append(tableInfo.fullname)
                    .append(update[0]).append("\"")
                    .append(update[1]).append(") == 1)\n");

            // Update query values
            StringBuilder[] where = getQueryAndArgs(whereColumns, null, false, false, " where ", " and ", false, tableData);

            StringBuilder columnQuery = new StringBuilder();
            for (String name : tableData.tableColumnNames) {
                if (!columnQuery.isEmpty()) columnQuery.append(',');
                quoteColumnName(columnQuery, name);
            }

            repoMethodBuilder.append("            return jdbc.query(\"select ").append(columnQuery)
                    .append(" from ").append(tableInfo.fullname)
                    .append(where[0]).append("\",tableMapper").append(where[1]).append(')')
                    .append(methodInfo.returnList ? ";\n" : ".get(0);\n");
            repoMethodBuilder.append("        return null;\n");
        } else {
            if (checkSuccess) repoMethodBuilder.append("        return ");
            else repoMethodBuilder.append("        ");

            repoMethodBuilder.append("jdbc.update(\"update ").append(tableInfo.fullname)
                    .append(update[0]).append("\"")
                    .append(update[1]).append(')')
                    .append(checkSuccess ? " < 1;\n" : ";\n");
        }
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private String generateClassDependencies(TableInfo tableInfo, TableData tableData, String template) {
        StringBuilder importBuilder = new StringBuilder();
        StringBuilder fieldsBuilder = new StringBuilder();
        StringBuilder paramBuilder = new StringBuilder();
        StringBuilder initBuilder = new StringBuilder();
        // Create field defamation
        for (Map.Entry<String, String> dependency : tableData.getDependencies().entrySet()) {
            String className = dependency.getKey().substring(dependency.getKey().lastIndexOf('.') + 1);
            String fieldName = dependency.getValue();

            importBuilder.append("import ").append(dependency.getKey()).append(";\n");
            if (fieldName != null) {
                fieldsBuilder.append("private final ").append(className).append(' ').append(fieldName).append(";\n    ");
                paramBuilder.append(',').append(className).append(' ').append(fieldName);
                initBuilder.append("this.").append(fieldName).append(" = ").append(fieldName).append(";\n        ");
            }
        }
        // Add table classpath also
        importBuilder.append("import ").append(tableInfo.classPath).append(";\n");

        template = template.replace("%CLASS_IMPORTS%", importBuilder);
        template = template.replace("%CLASS_FIELDS%", fieldsBuilder);
        template = template.replace("%CLASS_FIELDS_PARAMETER%", paramBuilder);
        template = template.replace("%CLASS_FIELDS_INIT%", initBuilder);
        return template;
    }

    private boolean generateColumnDefinition(ColumnInfo columnInfo, TableInfo tableInfo, StringBuilder tableCreateSql) {
        VariableElement field = columnInfo.field;
        TypeMirror type = field.asType();
        String columnType = toSqlType(type, columnInfo.column);
        if (columnType == null) {
            console.printMessage(ERROR, "Unknown SQL type for: " + field.asType().toString(), field);
            return true;
        }

        // column type
        tableCreateSql.append(' ').append(columnType);

        return false;
    }

    private boolean copyUtilityClasses() {
        String[] utilityClasses = {
                "IdentifierGenerator", "Snowflake", "FastRowMapper", "StringConverter", "FastResultSetExtractor"
        };

        for (String className : utilityClasses) {
            if (copyUtilityClass(className))
                return true;
        }
        return false;
    }

    private boolean copyUtilityClass(String className) {
        String classPackage = "com.wavjaby.jdbc.util";
        String path = '/' + classPackage.replace('.', '/') + '/' + className + ".java";
        String classPath = classPackage + '.' + className;

        try (InputStream inputStream = getClass().getResourceAsStream(path)) {
            if (inputStream == null) {
                console.printMessage(ERROR, "Could not find utility class resource: " + className);
                return true;
            }
            String sourceContent = getResourceAsString(inputStream);

            try (Writer out = processingEnv.getFiler().createSourceFile(classPath).openWriter()) {
                out.write(sourceContent);
            } catch (IOException e) {
                console.printMessage(ERROR, "Could not write utility class: '" + classPath + "'");
                return true;
            }
        } catch (IOException e) {
            console.printMessage(ERROR, "Error copying utility class " + className + ": " + e.getMessage());
            return true;
        }

        return false;
    }

    private static StringBuilder quoteColumnName(StringBuilder sb, String columnName) {
        return sb.append("\\\"").append(columnName).append("\\\"");
    }

    public static String toSqlType(TypeMirror type, Column column) {
        TypeKind kind = type.getKind();


        if (type instanceof DeclaredType declaredType) {
            String typeName = type.toString();
            // String
            if (typeName.startsWith(String.class.getName())) {
                if (column != null && column.length() != -1) {
                    return "VARCHAR(" + column.length() + ")";
                }
                return "VARCHAR";
            }

            // Check if type is java.util.List
            else if (declaredType.asElement().toString().equals(List.class.getName())) {
                TypeMirror componentType = declaredType.getTypeArguments().get(0);
                return toSqlType(componentType, column) + " ARRAY";
            } else if (type.toString().equals(java.sql.Date.class.getName())) {
                return "DATE";
            } else if (type.toString().equals(java.sql.Timestamp.class.getName())) {
                return "TIMESTAMP";
            }
            // Check if type is enum
            else if (declaredType.asElement() instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.ENUM) {
                if (column != null && column.length() != -1) {
                    return "VARCHAR(" + column.length() + ")";
                }
            } else if (typeName.startsWith(Boolean.class.getName())) kind = TypeKind.BOOLEAN;
            else if (typeName.startsWith(Byte.class.getName())) kind = TypeKind.BYTE;
            else if (typeName.startsWith(Short.class.getName())) kind = TypeKind.SHORT;
            else if (typeName.startsWith(Integer.class.getName())) kind = TypeKind.INT;
            else if (typeName.startsWith(Long.class.getName())) kind = TypeKind.LONG;
            else if (typeName.startsWith(Float.class.getName())) kind = TypeKind.FLOAT;
            else if (typeName.startsWith(Double.class.getName())) kind = TypeKind.DOUBLE;
            else if (typeName.startsWith(Character.class.getName())) kind = TypeKind.CHAR;
        }

        if (type instanceof ArrayType arrayType) {
            TypeMirror arrType = arrayType.getComponentType();

            // Auto-detect byte[] to BYTEA
            if (arrType.getKind() == TypeKind.BYTE)
                return "BYTEA";

            return toSqlType(arrType, column) + " ARRAY";
        }

        switch (kind) {
            case BOOLEAN:
                return "BOOLEAN";
            case BYTE:
//                return "TINYINT"; // H2 database
                return "SMALLINT";
            case SHORT:
                return "SMALLINT";
            case INT:
                return "INTEGER";
            case LONG:
                return "BIGINT";
            case FLOAT:
            case DOUBLE:
                if (column != null && column.precision() != 0) {
                    return column.scale() != 0
                            ? "NUMERIC(" + column.precision() + "," + column.scale() + ")"
                            : "NUMERIC(" + column.precision() + ")";
                } else {
                    if (kind == TypeKind.FLOAT)
                        return "FLOAT(24)";
                    return "FLOAT(53)";
                }
            case CHAR:
                return "NCHAR";
            default:
                return null;
        }
    }
}