package com.wavjaby.jdbc.processor;

import com.google.auto.service.AutoService;
import com.wavjaby.jdbc.Table;
import com.wavjaby.persistence.Column;
import com.wavjaby.persistence.QuerySQL;
import com.wavjaby.persistence.UniqueConstraint;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.wavjaby.jdbc.processor.AnnotationHelper.*;
import static com.wavjaby.jdbc.processor.ProcessorUtil.getResourceAsString;
import static javax.tools.Diagnostic.Kind.ERROR;


@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes("com.wavjaby.jdbc.Table")
@SuppressWarnings("unused")
public class TableProcessor extends AbstractProcessor {
    private final String repoTemplate, initTemplate;
    private Messager console;

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
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
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
        TypeElement repoInterfaceClass = (TypeElement) getAnnotationValueClass(tableMirror, "repositoryClass");
        if (repoInterfaceClass == null) {
            console.printMessage(ERROR, "The 'repositoryClass' is not given", element);
            return null;
        }
        TypeElement virtualBaseClass = (TypeElement) getAnnotationValueClass(tableMirror, "virtualBaseClass");

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

        builder.append("\"create view if not exists ");
        if (tableInfo.schema != null) builder.append(tableInfo.schema).append('.');
        builder.append(tableInfo.name).append(" as select ");
        List<ColumnInfo> primaryKey = new ArrayList<>();
        List<List<ColumnInfo>> uniqueKeyList = new ArrayList<>();
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
        List<ColumnInfo> primaryKey = new ArrayList<>();
        List<List<ColumnInfo>> uniqueKeyList = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (!first) builder.append(',');
            builder.append("\"+\n");
            first = false;

            // Create field
            boolean columnDef = columnInfo.column != null;

            // column name
            builder.append("        \"").append(columnInfo.columnName);

            // If column define by user
            if (columnDef && !columnInfo.column.columnDefinition().isEmpty()) {
                builder.append(' ').append(columnInfo.column.columnDefinition());
            }
            // Auto create column define
            else if (generateColumnDefinition(columnInfo, tableInfo, builder)) {
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

            if (entry.getValue().isPrimaryKey)
                primaryKey.add(columnInfo);

            if (columnDef && columnInfo.column.unique())
                uniqueKeyList.add(Collections.singletonList(columnInfo));
        }
        if (!primaryKey.isEmpty()) {
            builder.append(",\"+\n");
            builder.append("        \"constraint ").append(tableInfo.name).append("_PK");
            builder.append(" primary key(");
            for (int i = 0; i < primaryKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(primaryKey.get(i).columnName);
            }
            builder.append(")");
        }
        int index = 0;
        // For error handling
        AnnotationMirror constraintMirror = getAnnotationMirror(tableInfo.tableClassEle, Table.class);
        assert constraintMirror != null;
        List<? extends AnnotationValue> uniqueConstraints = getAnnotationValueList(constraintMirror, "uniqueConstraints");
        assert uniqueConstraints != null;
        // Get unique constraints
        for (UniqueConstraint uniqueConstraint : tableInfo.tableAnn.uniqueConstraints()) {
            AnnotationMirror uniqueConstraintMirror = getAnnotationMirrorFromValue(uniqueConstraints.get(index));
            if (uniqueConstraint.columnNames().length == 0 && uniqueConstraint.fieldNames().length == 0) {
                console.printMessage(ERROR, "Unique constraint must define at least one column or field", tableInfo.tableClassEle, uniqueConstraintMirror);
                return true;
            }
            List<ColumnInfo> uniqueKey = new ArrayList<>();
            // Fet unique constraint column by field name
            for (String name : uniqueConstraint.fieldNames()) {
                ColumnInfo columnInfo = tableData.tableFields.get(name);
                if (columnInfo == null) {
                    console.printMessage(ERROR, "Field '" + name + "' not exist in table '" + tableInfo.classPath + "'", tableInfo.tableClassEle, uniqueConstraintMirror);
                    return true;
                }
                uniqueKey.add(columnInfo);
            }
            // Fet unique constraint column by column name
            for (String name : uniqueConstraint.columnNames()) {
                if (!tableData.tableColumnNames.contains(name)) {
                    console.printMessage(ERROR, "Column '" + name + "' not exist in table: " + tableInfo.name, tableInfo.tableClassEle, uniqueConstraintMirror);
                    return true;
                }
                for (ColumnInfo columnInfo : tableData.tableFields.values()) {
                    if (!columnInfo.columnName.equals(name)) continue;
                    uniqueKey.add(columnInfo);
                    break;
                }
            }
            uniqueKeyList.add(uniqueKey);
            index++;
        }
        if (!uniqueKeyList.isEmpty()) {
            for (List<ColumnInfo> uniqueKey : uniqueKeyList) {
                builder.append(",\"+\n");
                builder.append("        \"constraint").append(tableInfo.getUniqueKey(uniqueKey)).append(" unique (");
                for (int i = 0; i < uniqueKey.size(); i++) {
                    if (i != 0) builder.append(',');
                    builder.append(uniqueKey.get(i).columnName);
                }
                builder.append(")");
            }
        }
        for (ColumnInfo column : tableData.tableFields.values()) {
            if (column.getReferencedColumnInfo() == null) continue;
            ColumnInfo referencedColumn = column.getReferencedColumnInfo();

            builder.append(",\"+\n");
            builder.append("        \"constraint ").append(column.getForeignKeyName()).append(" foreign key (")
                    .append(column.columnName).append(") references ");
            // If main table have schema, referenced table have to specify as well
            if (tableInfo.schema != null || referencedColumn.tableInfo.schema != null) {
                String referencedTableSchema = referencedColumn.tableInfo.schema;
                if (referencedTableSchema == null) referencedTableSchema = "PUBLIC";
                builder.append(referencedTableSchema).append(".");
            }
            builder.append(referencedColumn.tableInfo.name).append("(").append(referencedColumn.columnName).append(")");
        }

        builder.append(");\"");

        return false;
    }

    private boolean generateRepositoryMethods(TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        for (MethodInfo method : tableData.interfaceMethodInfo) {
            if (method.returnColumnSql != null) {
                if (generateRepositorySearchColumnSqlMethod(method, tableData, repoMethodBuilder))
                    return true;
            }
            // Check method to create
            else if (method.returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
                if (primitiveReturnType.getKind() == TypeKind.BOOLEAN) {
                    if (method.delete) {
                        if (generateRepositoryDeleteMethod(method, tableData, repoMethodBuilder))
                            return true;
                    } else if (method.modifyRow) {
                        if (generateRepositoryUpdateMethod(method, tableData, repoMethodBuilder, true, false))
                            return true;
                    } else {
                        if (generateRepositoryCheckMethod(method, tableData, repoMethodBuilder))
                            return true;
                    }
                } else if (method.returnTypeMirror.getKind() == TypeKind.INT) {
                    if (method.count) {
                        if (generateRepositoryCountMethod(method, tableData, repoMethodBuilder))
                            return true;
                    } else if (method.delete) {
                        if (generateRepositoryDeleteMethod(method, tableData, repoMethodBuilder))
                            return true;
                    } else {
                        console.printMessage(ERROR, "Unsupported method return type: " + method.returnTypeMirror, method.method);
                        return true;
                    }
                }
            } else if (method.returnTypeMirror instanceof NoType voidReturnType) {
                if (method.delete) {
                    if (generateRepositoryDeleteMethod(method, tableData, repoMethodBuilder))
                        return true;
                } else if (method.modifyRow) {
                    if (generateRepositoryUpdateMethod(method, tableData, repoMethodBuilder, false, false))
                        return true;
                } else {
                    console.printMessage(ERROR, "Unsupported method return type: void", method.method);
                    return true;
                }
            } else {
                if (method.returnSelfTable) {
                    // Update function
                    if (method.modifyRow) {
                        if (generateRepositoryUpdateMethod(method, tableData, repoMethodBuilder, false, true))
                            return true;
                    }
                    // Insert function
                    else if (method.insertMethod) {
                        if (generateRepositoryInsertMethod(method, tableData, repoMethodBuilder))
                            return true;
                    }
                    // Query function
                    else if (generateRepositorySearchMethod(method, tableData, repoMethodBuilder))
                        return true;
                } else if (method.returnColumn != null) {
                    if (generateRepositorySearchColumnMethod(method, tableData, repoMethodBuilder))
                        return true;
                } else {
                    if (generateRepositorySearchMethod(method, tableData, repoMethodBuilder))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Constructs SQL query fragments and corresponding parameter arguments based on method parameters
     * and additional query constraints.
     *
     * @param params           List of method parameter information containing column mappings and metadata
     * @param extraQuerySQL    Additional query SQL constraints to append (can be null)
     * @param update           true if generating SET clause for UPDATE operation, false for WHERE clause
     * @param prefix           SQL prefix to prepend (e.g., " WHERE ", " SET "), can be null
     * @param conjunction      SQL conjunction operator between parameters (e.g., " AND ", ",")
     * @param tableConstructor true if formatting arguments for table constructor, false for prepared statement
     * @param tableData        Table metadata used for dependency management and column information
     * @return Array containing two StringBuilder objects: [0] = query fragment, [1] = arguments list
     */
    private StringBuilder[] getQueryAndArgs(List<MethodParamInfo> params, QuerySQL extraQuerySQL, boolean update, String prefix, String conjunction, boolean tableConstructor, TableData tableData) {
        StringBuilder queryBuilder = new StringBuilder();
        StringBuilder argsBuilder = new StringBuilder();

        if (!tableConstructor && !params.isEmpty() || extraQuerySQL != null) {
            if (prefix != null) queryBuilder.append(prefix);
        }
        if (!tableConstructor && !params.isEmpty()) {
            argsBuilder.append(',');
        }

        int tempVarCount = 0;

        int i = -1;
        for (MethodParamInfo param : params) {
            if (++i != 0) queryBuilder.append(conjunction);

            // Query with multiple columns
            if (!update && param.columns.size() > 1)
                queryBuilder.append("(");
            int j = -1;
            for (ColumnInfo column : param.columns) {
                if (param.dataClass && column.idGenerator != null)
                    continue;

                if (update) {
                    if (++j != 0) queryBuilder.append(',');
                } else {
                    if (++j != 0) queryBuilder.append(" or ");
                }

                // Query where with ignore case
                if (!update && param.ignoreCase)
                    queryBuilder.append("LOWER(").append(column.columnName).append(") ")
                            .append(param.whereOperation).append(" LOWER(?)");
                else
                    queryBuilder.append(column.columnName).append(param.whereOperation).append('?');

                String argName = param.paramName;
                // Get field if using data class
                if (param.dataClass) {
                    argName += '.' + column.field.getSimpleName().toString();
                    if (param.isRecord) argName += "()";
                }

                // Support modify array
                if (i + j != 0) argsBuilder.append(',');
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
            if (!update && param.columns.size() > 1)
                queryBuilder.append(")");
        }
        if (extraQuerySQL != null) {
            if (!params.isEmpty())
                queryBuilder.append(' ').append(extraQuerySQL.conjunction()).append(' ');
            queryBuilder.append(extraQuerySQL.value());
        }
        return new StringBuilder[]{queryBuilder, argsBuilder};
    }

    private StringBuilder[] updateQueryAndArgs(List<MethodParamInfo> whereColumns, List<MethodParamInfo> updateColumns, QuerySQL querySQL, TableData tableData) {
        StringBuilder[] where = getQueryAndArgs(whereColumns, querySQL, false, " where ", " and ", false, tableData);
        StringBuilder[] values = getQueryAndArgs(updateColumns, null, true, " set ", ",", false, tableData);

        values[0].append(where[0]);
        values[1].append(where[1]);

        return new StringBuilder[]{values[0], values[1]};
    }

    private StringBuilder getClassDefinition(MethodInfo methodInfo) {
        return getClassDefinition(methodInfo.returnType, methodInfo);
    }

    private StringBuilder getClassDefinition(String returnType, MethodInfo methodInfo) {

        // Build method definition
        StringBuilder builder = new StringBuilder();
        builder.append("\n    public ");

        if (methodInfo.returnList) builder.append("List<").append(returnType).append(">");
        else builder.append(returnType);

        builder.append(' ').append(methodInfo.methodName).append('(');
        // Add method param
        for (int i = 0; i < methodInfo.params.size(); ++i) {
            if (i != 0) builder.append(", ");
            MethodParamInfo param = methodInfo.params.get(i);
            String typeStr = param.paramType;
            if (typeStr.equals(String.class.getName()))
                typeStr = "String";
            // Add param to builder
            builder.append(typeStr).append(' ').append(param.paramName);
        }
        builder.append(") {\n");
        return builder;
    }

    private boolean generateRepositoryInsertMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        List<? extends VariableElement> parameters = methodInfo.method.getParameters();
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
            infos.add(i, new MethodParamInfo(null, Collections.singletonList(info), "long", "id" + i, false, null));
        }

        checkAndConvertEnumToStringArray(repoMethodBuilder, infos);

        StringBuilder[] values = getQueryAndArgs(infos, null, true, " set ", ",", false, tableData);

        repoMethodBuilder.append("        jdbc.update(\"insert into ").append(tableInfo.fullname)
                .append(values[0]).append('"').append(values[1]).append(");\n");

        // Create return
        values = getQueryAndArgs(infos, null, true, null, ",", true, tableData);
        repoMethodBuilder.append("        return new ").append(tableInfo.className).append('(')
                .append(values[1]).append(");\n");

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
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo.querySQL, false, " where ", " and ", false, tableData);
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
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo.querySQL, false, " where ", " and ", false, tableData);
        repoMethodBuilder.append("        return jdbc.queryForObject(\"select count(*) from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append("\"")
                .append(",Integer.class").append(queryWithArgs[1]).append(");\n");
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositorySearchColumnMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        String returnTypeStr = methodInfo.returnType;
        StringBuilder methodDef = getClassDefinition(methodInfo);
        repoMethodBuilder.append(methodDef);

        // Build method body, SQL query part
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo.querySQL, false, " where ", " and ", false, tableData);
        if (methodInfo.returnList)
            repoMethodBuilder.append("        return");
        else
            repoMethodBuilder.append("        List<").append(returnTypeStr).append("> result =");
        repoMethodBuilder.append(" jdbc.queryForList(\"select ")
                .append(methodInfo.returnColumn.columnName).append(" from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append(sqlResultModifier(methodInfo)).append("\"")
                .append(',').append(returnTypeStr).append(".class").append(queryWithArgs[1]).append(");\n");

        if (!methodInfo.returnList)
            repoMethodBuilder.append("        return result.isEmpty() ? null : result.get(0);\n");
        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositorySearchColumnSqlMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder) {
        TableInfo tableInfo = tableData.tableInfo;
        // Build method definition
        String returnTypeStr = methodInfo.returnType;
        StringBuilder methodDef = getClassDefinition(methodInfo);
        repoMethodBuilder.append(methodDef);

        // Process SQL query part
        String returnColumnSql = methodInfo.returnColumnSql.replaceAll(" *\r?\n *", " ");
        StringBuilder columnQuery = new StringBuilder();
        List<String> extraParam = new ArrayList<>();
        Pattern pattern = Pattern.compile(":[a-zA-Z_][a-zA-Z0-9_]+");
        Matcher matcher = pattern.matcher(returnColumnSql);
        int index = 0;
        while (matcher.find()) {
            String pramName = matcher.group().substring(1);
            boolean pramExist = methodInfo.params.stream().anyMatch(param -> param.paramName.equals(pramName));
            if (!pramExist)
                continue;

            extraParam.add(pramName);
            columnQuery.append(returnColumnSql, index, matcher.start()).append('?');
            index = matcher.end();
        }
        columnQuery.append(returnColumnSql, index, returnColumnSql.length());

        // Build method body, SQL query part
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo.querySQL, false, " where ", " and ", false, tableData);

        // Add extra args
        if (!extraParam.isEmpty()) {
            if (!queryWithArgs[1].isEmpty()) queryWithArgs[1].append(',');
            queryWithArgs[1].append(String.join(",", extraParam));
        }

        if (methodInfo.returnList)
            repoMethodBuilder.append("        return jdbc.queryForList");
        else
            repoMethodBuilder.append("        return jdbc.queryForObject");
        repoMethodBuilder.append("(\"select ")
                .append(columnQuery).append(" from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append(sqlResultModifier(methodInfo)).append("\"")
                .append(',').append(returnTypeStr).append(".class").append(queryWithArgs[1]).append(");\n");

//        if (!methodInfo.returnList)
//            repoMethodBuilder.append("        return result.isEmpty() ? null : result.get(0);\n");
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
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo.querySQL, false, " where ", " and ", false, tableData);
        if (methodInfo.returnList)
            repoMethodBuilder.append("        return");
        else
            repoMethodBuilder.append("        List<").append(returnTypeStr).append("> result =");


        String columns = String.join(",", tableData.tableColumnNames);

        repoMethodBuilder.append(" jdbc.query(\"select ").append(columns).append(" from ").append(tableInfo.fullname)
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
        StringBuilder[] queryWithArgs = getQueryAndArgs(methodInfo.params, methodInfo.querySQL, false, " where ", " and ", false, tableData);
        if (returnType != TypeKind.VOID) repoMethodBuilder.append("        return");
        else repoMethodBuilder.append("        ");

        repoMethodBuilder.append(" jdbc.update(\"delete from ").append(tableInfo.fullname)
                .append(queryWithArgs[0]).append("\"").append(queryWithArgs[1]).append(")")
                .append(returnType == TypeKind.BOOLEAN ? " < 1;\n" : ";\n");

        repoMethodBuilder.append("    }\n");
        return false;
    }

    private boolean generateRepositoryUpdateMethod(MethodInfo methodInfo, TableData tableData, StringBuilder repoMethodBuilder, boolean checkSuccess, boolean returnTable) {
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
        StringBuilder[] update = updateQueryAndArgs(whereColumns, updateColumns, methodInfo.querySQL, tableData);
        if (returnTable) {
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
            StringBuilder[] where = getQueryAndArgs(whereColumns, null, false, " where ", " and ", false, tableData);
            String columns = String.join(",", tableData.tableColumnNames);
            repoMethodBuilder.append("            return jdbc.query(\"select ").append(columns)
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
        TypeKind type = field.asType().getKind();
        String columnType = toSqlType(field, type, columnInfo.column, false);
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

    public static String toSqlType(Element field, TypeKind type, Column column, boolean typeOnly) {
        boolean string = false;
        if (type == TypeKind.DECLARED) {
            String typeName = field.asType().toString();
            // String
            if (typeName.startsWith(String.class.getName())) string = true;
            else if (typeName.startsWith(Boolean.class.getName())) type = TypeKind.BOOLEAN;
            else if (typeName.startsWith(Byte.class.getName())) type = TypeKind.BYTE;
            else if (typeName.startsWith(Short.class.getName())) type = TypeKind.SHORT;
            else if (typeName.startsWith(Integer.class.getName())) type = TypeKind.INT;
            else if (typeName.startsWith(Long.class.getName())) type = TypeKind.LONG;
            else if (typeName.startsWith(Float.class.getName())) type = TypeKind.FLOAT;
            else if (typeName.startsWith(Double.class.getName())) type = TypeKind.DOUBLE;
            else if (typeName.startsWith(Character.class.getName())) type = TypeKind.CHAR;
        }

        switch (type) {
            case BOOLEAN:
                return "BOOLEAN";
            case BYTE:
                return "TINYINT";
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
                    if (type == TypeKind.FLOAT)
                        return "FLOAT(24)";
                    return "FLOAT(53)";
                }
            case CHAR:
                return "NCHAR";
            case ARRAY:
                TypeKind arrType = ((ArrayType) field.asType()).getComponentType().getKind();
                if (typeOnly)
                    return toSqlType(field, arrType, column, true);
                return toSqlType(field, arrType, column, false) + " ARRAY";
            case DECLARED:
                if (string) {
                    if (column != null && column.length() != -1) {
                        return "VARCHAR(" + column.length() + ")";
                    }
                    return "VARCHAR";
                } else if (field.asType().toString().equals(java.sql.Date.class.getName())) {
                    return "DATE";
                } else if (field.asType().toString().equals(java.sql.Timestamp.class.getName())) {
                    return "TIMESTAMP";
                }
            default:
                return null;
        }
    }
}