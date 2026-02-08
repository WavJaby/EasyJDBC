package com.wavjaby.jdbc.processor.model;

import com.wavjaby.persistence.*;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

public class MethodInfo {
    private final static Pattern paramPattern = Pattern.compile(":([a-zA-Z_]\\w*)(\\.\\w+)*");
    private final TableData tableData;
    public final ExecutableElement method;

    public final TypeMirror returnTypeMirror;
    public final String methodName;
    public final List<MethodParamInfo> params = new ArrayList<>();
    // Operation
    public final boolean modifyRow;
    public final boolean delete;
    public final boolean count;
    // Order by
    public final Order.ByField[] orderBy;
    public ColumnInfo[] orderByColumns;
    // Limit
    public final Integer limit;
    // Extra query settings
    public final QuerySQL querySql;
    public final List<QueryParamInfo> querySqlParams = new ArrayList<>();
    // Select return type
    private final String returnField;
    private final String returnColumnSql;
    public final List<QueryParamInfo> returnColumnSqlParams = new ArrayList<>();

    // Return type
    public boolean returnSelfTable;
    public boolean returnList;
    public String returnTypeName;
    public ColumnInfo returnColumn;
    // Insert method
    public final boolean batchInsert;
    public boolean insertMethod;

    public MethodInfo(ExecutableElement method, TableData tableData) {
        this.method = method;
        this.tableData = tableData;
        this.methodName = method.getSimpleName().toString();
        this.returnTypeMirror = method.getReturnType();

        this.modifyRow = method.getAnnotation(Modifying.class) != null;
        this.delete = method.getAnnotation(Delete.class) != null;
        this.count = method.getAnnotation(Count.class) != null;

        Order order = method.getAnnotation(Order.class);
        this.orderBy = order == null ? null : order.value();

        Limit limit = method.getAnnotation(Limit.class);
        this.limit = limit == null ? null : limit.value();

        QuerySQL extraQuerySQL = method.getAnnotation(QuerySQL.class);
        this.querySql = extraQuerySQL == null || extraQuerySQL.value().isEmpty() ? null : extraQuerySQL;

        Select select = method.getAnnotation(Select.class);
        this.returnField = select == null || select.field().isEmpty() ? null : select.field();
        this.returnColumnSql = select == null || select.columnSql().isEmpty() ? null : select.columnSql();

        BatchInsert batchInsert = method.getAnnotation(BatchInsert.class);
        this.batchInsert = batchInsert != null;
    }

    public boolean parseMethod(Messager console) {
        if (parseParamsToColumns(console)) return true;

        // Check custom SQL parameters are defined in method parameters
        for (QueryParamInfo param : querySqlParams) {
            if (param.paramName == null || param.isMethodParamExist()) {
                continue;
            }
            printError(console, method, QuerySQL.class, "value", "Parameter '" + param.paramName + "' is not defined in method parameters");
            return true;
        }

        for (QueryParamInfo param : returnColumnSqlParams) {
            if (param.paramName == null || param.isMethodParamExist()) {
                continue;
            }
            printError(console, method, Select.class, "columnSql", "Parameter '" + param.paramName + "' is not defined in method parameters");
        }

        // Check modifier
        if (orderBy != null) {
            orderByColumns = new ColumnInfo[orderBy.length];
            for (int i = 0; i < orderBy.length; i++) {
                String fieldName = orderBy[i].value();
                if (fieldName.isBlank()) {
                    printError(console, method, Order.class, "field", "Field name can not be blank");
                    return true;
                }
                ColumnInfo column = tableData.tableFields.get(fieldName);
                if (column == null) {
                    printError(console, method, Order.class, "field",
                            "Field name '" + fieldName + "' not exist in table " + tableData.tableInfo.classPath);
                    return true;
                }
                orderByColumns[i] = column;
            }
        }

        // Check @Count method return type
        if (this.count && (!(returnTypeMirror instanceof PrimitiveType primitiveReturnType) || primitiveReturnType.getKind() != TypeKind.INT)) {
            printError(console, method, Count.class, null, "Count method must return int");
            return true;
        }

        // Check method to create
        if (returnTypeMirror instanceof DeclaredType declaredReturnType) {
            String typeClassPath = declaredReturnType.asElement().toString();
            // Check if return table itself
            if (typeClassPath.equals(tableData.tableInfo.classPath)) {
                this.returnTypeName = tableData.tableInfo.className;
                this.returnSelfTable = true;
                return false;
            }
            // Check if return class is subclass of table class
            if (checkInstanceof(tableData.tableInfo.tableClassEle, (TypeElement) declaredReturnType.asElement())) {
                this.returnTypeName = tableData.tableInfo.className;
                this.returnSelfTable = true;
                return false;
            }

            // Print error if both return field and sql are used
            if (returnField != null && returnColumnSql != null) {
                printError(console, method, Select.class, "field",
                        "Return field and column SQL can not be used together");
                return true;
            }

            // Check if the return field is valid
            if (returnField != null || returnColumnSql != null) {
                if (returnField != null) {
                    // Check field exit
                    ColumnInfo column = tableData.tableFields.get(returnField);
                    if (column == null) {
                        printError(console, method, Select.class, "field",
                                "Return field '" + returnField + "' not exist in table " + tableData.tableInfo.classPath);
                        return true;
                    }
                    this.returnColumn = column;
                }

                this.returnTypeName = getTypeName(returnTypeMirror);
            }

            // Check if return type is List
            if (typeClassPath.equals(List.class.getName())) {
                this.returnList = true;

                TypeMirror genericSuperType = declaredReturnType.getTypeArguments().get(0);
                // Check generic type is valid
                if (genericSuperType.toString().equals(tableData.tableInfo.classPath)) {
                    this.returnSelfTable = true;
                    this.returnTypeName = tableData.tableInfo.className;
                    return false;
                } else if (returnField != null || returnColumnSql != null) {
                    this.returnTypeName = getTypeName(genericSuperType);
                    return false;
                }
            }
            // Check if return column
            else if (returnField != null || returnColumnSql != null) {
                return false;
            }
        } else if (returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
            if (primitiveReturnType.getKind() == TypeKind.BOOLEAN) {
                this.returnTypeName = "boolean";
                return false;
            }
            if (primitiveReturnType.getKind() == TypeKind.INT) {
                this.returnTypeName = "int";
                return false;
            }
        } else if (returnTypeMirror instanceof ArrayType arrayType) {
            if (!(arrayType.getComponentType() instanceof DeclaredType) && arrayType.getComponentType().getKind() != TypeKind.BYTE) {
                console.printMessage(ERROR, "Primitive array is not supported: " + arrayType + ", use List or DeclaredType array instead.", method);
                return true;
            }

            // Only allow when return field is used
            if (returnField != null) {
                // Check field exit
                ColumnInfo column = tableData.tableFields.get(returnField);
                if (column == null) {
                    printError(console, method, Select.class, "field",
                            "Return field '" + returnField + "' not exist in table " + tableData.tableInfo.classPath);
                    return true;
                }
                this.returnTypeName = getTypeName(returnTypeMirror);
                this.returnColumn = column;
                return false;
            } else {
                console.printMessage(ERROR, "Return with array type must use returnField, to query multiple objects, use List<>", method);
                return true;
            }
        } else if (returnTypeMirror instanceof NoType) {
            this.returnTypeName = "void";
            return false;
        }

        console.printMessage(ERROR, "Repository '" + tableData.tableInfo.repoIntPackagePath +
                "' for table class '" + tableData.tableInfo.classPath +
                "', method: '" + method.toString() + "' return type '" + returnTypeMirror + "' is not acceptable", method);
        return true;
    }

    public String getReturnListType() {
        String typeStr = returnTypeName;
        if (returnTypeMirror instanceof PrimitiveType primitiveType) {
            return switch (primitiveType.getKind()) {
                case BOOLEAN -> "Boolean";
                case BYTE -> "Byte";
                case SHORT -> "Short";
                case INT -> "Integer";
                case LONG -> "Long";
                case CHAR -> "Character";
                case FLOAT -> "Float";
                case DOUBLE -> "Double";
                default -> typeStr;
            };
        }
        return typeStr;
    }

    private static String getTypeName(TypeMirror typeMirror) {
        String typeStr = typeMirror.toString();
        if (typeStr.startsWith("java.lang.")) return typeStr.substring(10);
        return typeStr;
    }

    private static boolean parseSqlParam(String sql, List<QueryParamInfo> params) {
        String returnColumnSql = sql.replaceAll(" *\r?\n *", " ");
        Matcher matcher = paramPattern.matcher(returnColumnSql);
        int index = 0;
        while (matcher.find()) {
            String pramName = matcher.group().substring(1);

            params.add(new QueryParamInfo(returnColumnSql.substring(index, matcher.start()), pramName));
            index = matcher.end();
        }
        params.add(new QueryParamInfo(returnColumnSql.substring(index), null));
        return true;
    }

    private boolean isSqlParamExist(String paramName) {
        return querySqlParams.stream().anyMatch(param -> paramName.equals(param.paramName)) ||
                returnColumnSqlParams.stream().anyMatch(param -> paramName.equals(param.paramName));
    }

    private void setSqlParamDefined(String paramName, MethodParamInfo methodParamInfo) {
        for (QueryParamInfo param : querySqlParams) {
            if (param.paramName != null && param.paramName.equals(paramName)) {
                param.setMethodParamInfo(methodParamInfo);
            }
        }

        for (QueryParamInfo param : returnColumnSqlParams) {
            if (param.paramName != null && param.paramName.equals(paramName)) {
                param.setMethodParamInfo(methodParamInfo);
            }
        }
    }

    public boolean parseParamsToColumns(Messager console) {
        List<? extends VariableElement> methodParams = method.getParameters();

        if (returnColumnSql != null) {
            // Process SQL query part
            if (!parseSqlParam(returnColumnSql, returnColumnSqlParams)) {
                console.printMessage(ERROR, "Invalid SQL query: " + returnColumnSql, method);
                return false;
            }
        }

        if (querySql != null) {
            if (!parseSqlParam(querySql.value(), querySqlParams)) {
                console.printMessage(ERROR, "Invalid SQL query: " + querySql.value(), method);
                return false;
            }
        }

        if (batchInsert) {
            if (methodParams.size() == 1 &&
                    methodParams.get(0).asType() instanceof DeclaredType declaredType &&
                    // Check param is List<tableClass>
                    declaredType.asElement().toString().equals(List.class.getName()) &&
                    declaredType.getTypeArguments().get(0) instanceof DeclaredType genericSuperType &&
                    genericSuperType.toString().equals(tableData.tableInfo.classPath) &&
                    // Check return type is int
                    this.returnTypeMirror instanceof PrimitiveType primitiveReturnType &&
                    primitiveReturnType.getKind() == TypeKind.INT) {
                insertMethod = true;
                return addElement(genericSuperType.asElement(), methodParams.get(0).getSimpleName().toString(), console);
            } else {
                console.printMessage(ERROR, "@BatchInsert should use List<TableClass> as parameter and return int", method);
                return true;
            }
        }

        // Check if param is table class
        VariableElement selfTableParam = null;
        for (VariableElement parameter : methodParams) {
            if (parameter.asType() instanceof DeclaredType declaredType &&
                    declaredType.toString().equals(tableData.tableInfo.classPath)) {
                insertMethod = true;
                selfTableParam = parameter;
            }
        }
        if (selfTableParam != null) {
            if (methodParams.size() > 1) {
                StringBuilder paramsStr = new StringBuilder();
                for (VariableElement parameter : methodParams) {
                    if (parameter == selfTableParam) continue;
                    if (!paramsStr.isEmpty())
                        paramsStr.append(',');
                    paramsStr.append(parameter.getSimpleName());
                }
                console.printMessage(ERROR, "Parameter with table class can not use with other parameters: '" + paramsStr + "'", method);
                return true;
            }
            return addElement(selfTableParam, console);
        }

        // Get method params as column name
        for (VariableElement parameter : methodParams) {
            if (addElement(parameter, console))
                return true;
        }
        return false;
    }

    private boolean addElement(VariableElement parameter, Messager console) {
        String parameterName = parameter.getSimpleName().toString();
        return addElement(parameter, parameterName, console);
    }

    private boolean addElement(Element parameter, String parameterName, Messager console) {
        TypeMirror parameterType = parameter.asType();

        // Custom param name
        FieldName fieldName = parameter.getAnnotation(FieldName.class);
        Where where = parameter.getAnnotation(Where.class);
        UpdateData updateData = parameter.getAnnotation(UpdateData.class);

        if (updateData != null && (fieldName != null || where != null)) {
            console.printMessage(ERROR, "@UpdateData can not use with @FieldName or @Where annotation", parameter, getAnnotationMirror(parameter, UpdateData.class));
            return true;
        }
        if (fieldName != null && where != null) {
            console.printMessage(ERROR, "@Where can not use with @FieldName annotation", parameter, getAnnotationMirror(parameter, Where.class));
            return true;
        }

        // Process class data
        if ((parameter.asType() instanceof DeclaredType declaredType) &&
                !declaredType.asElement().getKind().equals(ElementKind.ENUM) &&
                !declaredType.toString().startsWith("java.lang.") &&
                !declaredType.toString().startsWith("java.sql.") &&
                !declaredType.toString().startsWith("java.io.")) {
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            return addClassFieldsColumn(typeElement, parameterName, parameter, console);
        }

        return addParamColumn(parameterType, parameterName, fieldName != null ? fieldName.value() : parameterName, where, parameter, console);
    }

    private boolean addClassFieldsColumn(TypeElement classType, String parameterName, Element parameter, Messager console) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(classType, fields);

        List<ColumnInfo> columns = new ArrayList<>();
        // List all fields in class
        boolean error = false;
        for (VariableElement element : fields.values()) {
            String fieldType = element.asType().toString();
            String fieldName = element.getSimpleName().toString();

            ColumnInfo column = tableData.tableFields.get(fieldName);

            if (checkFieldTypeAndName(fieldType, fieldName, parameter, parameterName, classType, false, console, column)) {
                error = true;
                continue;
            }

            // Skip field for query
            if (element.getAnnotation(Where.class) != null)
                continue;

            columns.add(column);
        }
        params.add(new MethodParamInfo(parameter, columns, getTypeName(classType.asType()), parameterName, true, null, false));
        return error;
    }

    private boolean addParamColumn(TypeMirror parameterType, String parameterName, String tableFieldName, Where where,
                                   Element parameter, Messager console) {
        List<ColumnInfo> columns = new ArrayList<>();
        boolean customSqlParam = false;
        MethodParamInfo methodParamInfo;
        // Adds columns based on `@Where` annotation or field name
        if (where != null) {
            boolean error = false;
            String[] fieldNames = where.value().length > 0
                    ? where.value()
                    : new String[]{parameterName};

            for (String fieldName : fieldNames) {
                ColumnInfo column = tableData.tableFields.get(fieldName);
                if (checkFieldTypeAndName(parameterType.toString(), fieldName, parameter, parameterName, null, false, console, column)) {
                    error = true;
                    continue;
                }

                // Check ignore case is used on string type only
                if (where.ignoreCase() && !parameterType.toString().equals(String.class.getName())) {
                    AnnotationMirror whereMirror = getAnnotationMirror(parameter, Where.class);
                    console.printMessage(ERROR, "ignoreCase can only be used with String type parameter",
                            parameter, whereMirror, getAnnotationValue(whereMirror, "ignoreCase"));
                    error = true;
                }

                columns.add(column);

            }

            // Check if method parameter is for SQL parameter
            if (isSqlParamExist(parameterName)) {
                customSqlParam = true;
            }
            if (error) return true;

            methodParamInfo = new MethodParamInfo(parameter, columns, getTypeName(parameterType), parameterName, false, where, customSqlParam);
            setSqlParamDefined(parameterName, methodParamInfo);
        }
        // Add column based on field name
        else {
            ColumnInfo column = tableData.tableFields.get(tableFieldName);
            if (checkFieldTypeAndName(parameterType.toString(), tableFieldName, parameter, parameterName, null, true, console, column)) {
                return true;
            }
            // If method parameter is for SQL parameter, the column will be null
            if (column != null)
                columns.add(column);
            else
                customSqlParam = true;

            methodParamInfo = new MethodParamInfo(parameter, columns, getTypeName(parameterType), parameterName, false, null, customSqlParam);
            setSqlParamDefined(parameterName, methodParamInfo);
        }

        params.add(methodParamInfo);
        return false;
    }

    private boolean checkFieldTypeAndName(String parameterType, String fieldName, Element parameter, String parameterName, TypeElement classType, boolean allowSqlParam, Messager console, ColumnInfo column) {
//        if (classType != null)
//            fieldName = classType.asType() + "::" + fieldName;

        // field for column exist and type is same
        if (column != null && column.field.getSimpleName().toString().equals(fieldName)) {
            return false;
        }

        // Skip check if parameter is for sql parameter
        if (allowSqlParam && isSqlParamExist(fieldName))
            return false;

        String inClass = classType != null ? "(in class: '" + classType.asType().toString() + "', parameter: '" + parameterName + "')" : "";
        // Column not exist
        if (column == null) {
            console.printMessage(ERROR, "Field '" + fieldName + "'" + inClass + " does not exist in table '" + tableData.tableInfo.classPath + "'", parameter);
        }
        // Type mismatch
        else {
            console.printMessage(ERROR,
                    "Parameter type '" + parameterType + "'" + inClass + " does not match field type '" + column.field.asType().toString() +
                            "' for column '" + column.columnName + "' in table '" + tableData.tableInfo.classPath + "'", parameter);
        }
        return true;
    }
}
