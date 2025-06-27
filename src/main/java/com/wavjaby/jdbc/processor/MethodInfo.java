package com.wavjaby.jdbc.processor;

import com.wavjaby.persistence.*;
import com.wavjaby.persistence.conf.Direction;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wavjaby.jdbc.processor.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

public class MethodInfo {
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
    public final Direction orderBy;
    public final String orderByField;
    // Limit
    public final Integer limit;
    // Extra query settings
    public final QuerySQL querySQL;
    // Select return type
    public final String returnField;
    public final String returnColumnSql;
    public boolean returnSelfTable;
    public boolean returnList;
    public String returnType;

    public boolean insertMethod;
    public ColumnInfo returnColumn;

    public MethodInfo(ExecutableElement method, TableData tableData) {
        this.method = method;
        this.tableData = tableData;
        this.methodName = method.getSimpleName().toString();
        this.returnTypeMirror = method.getReturnType();

        this.modifyRow = method.getAnnotation(Modifying.class) != null;
        this.delete = method.getAnnotation(Delete.class) != null;
        this.count = method.getAnnotation(Count.class) != null;

        OrderBy orderBy = method.getAnnotation(OrderBy.class);
        this.orderBy = orderBy == null ? null : orderBy.direction();
        this.orderByField = orderBy == null ? null : orderBy.field();

        Limit limit = method.getAnnotation(Limit.class);
        this.limit = limit == null ? null : limit.value();

        QuerySQL extraQuerySQL = method.getAnnotation(QuerySQL.class);
        this.querySQL = extraQuerySQL == null || extraQuerySQL.value().isEmpty() ? null : extraQuerySQL;

        Select select = method.getAnnotation(Select.class);
        this.returnField = select == null || select.field().isEmpty() ? null : select.field();
        this.returnColumnSql = select == null || select.columnSql().isEmpty() ? null : select.columnSql();
    }

    public boolean parseMethod(Messager console) {
        if (parseParamsToColumns(console)) return true;
        // Check modifier
        if (orderByField != null) {
            if (orderByField.isBlank()) {
                printError(console, method, OrderBy.class, "field",
                        "Field name can not be blank");
                return true;
            }
            if (!tableData.tableFields.containsKey(orderByField)) {
                printError(console, method, OrderBy.class, "field",
                        "Field name '" + orderByField + "' not exist in table " + tableData.tableInfo.classPath);
                return true;
            }
        }

        // Check method to create
        if (returnTypeMirror instanceof DeclaredType declaredReturnType) {
            String typeClassPath = declaredReturnType.asElement().toString();
            // Check if return table itself
            if (typeClassPath.equals(tableData.tableInfo.classPath)) {
                this.returnType = tableData.tableInfo.className;
                this.returnSelfTable = true;
                return false;
            }
            // Check if return class is subclass of table class
            if (checkInstanceof(tableData.tableInfo.tableClassEle, (TypeElement) declaredReturnType.asElement())) {
                this.returnType = tableData.tableInfo.className;
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
            if (returnField != null) {
                // Check field exit
                ColumnInfo column = tableData.tableFields.get(returnField);
                if (column == null) {
                    printError(console, method, Select.class, "field",
                            "Return field '" + returnField + "' not exist in table " + tableData.tableInfo.classPath);
                    return true;
                }
                String returnTypeStr = returnTypeMirror.toString();
                if (returnTypeStr.startsWith("java.lang.")) returnTypeStr = returnTypeStr.substring(10);
                this.returnType = returnTypeStr;
                this.returnColumn = column;
            }

            // Check if return type is List
            if (typeClassPath.equals(List.class.getName())) {
                this.returnList = true;

                TypeMirror genericSuperType = declaredReturnType.getTypeArguments().get(0);
                // Check generic type is valid
                if (genericSuperType.toString().equals(tableData.tableInfo.classPath)) {
                    this.returnType = tableData.tableInfo.className;
                    return false;
                } else if (returnField != null || returnColumnSql != null) {
                    String returnTypeStr = genericSuperType.toString();
                    if (returnTypeStr.startsWith("java.lang.")) returnTypeStr = returnTypeStr.substring(10);
                    this.returnType = returnTypeStr;
                    return false;
                }
            }
            // Check if return column
            else if (returnField != null || returnColumnSql != null) {
                return false;
            }
        } else if (returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
            if (primitiveReturnType.getKind() == TypeKind.BOOLEAN)
                return false;
            if (primitiveReturnType.getKind() == TypeKind.INT)
                return false;
        } else if (returnTypeMirror instanceof NoType) {
            this.returnType = "void";
            return false;
        }

        console.printMessage(ERROR, "Repository '" + tableData.tableInfo.repoIntClassPath +
                "' for table class '" + tableData.tableInfo.classPath +
                "', method: '" + method.toString() + "' return type '" + returnTypeMirror + "' is not acceptable", method);
        return true;
    }

    public boolean parseParamsToColumns(Messager console) {
        List<? extends VariableElement> methodParams = method.getParameters();

        // Check if param is table class
        if (methodParams.size() == 1 &&
                methodParams.get(0).asType() instanceof DeclaredType declaredType &&
                declaredType.toString().equals(tableData.tableInfo.classPath)) {
            VariableElement parameter = methodParams.get(0);
            insertMethod = true;
            return addElement(parameter, console);
        }

        // Get method params as column name
        for (VariableElement parameter : methodParams) {
            if (addElement(parameter, console))
                return true;
        }
        return false;
    }

    private boolean addElement(Element parameter, Messager console) {
        String parameterType = parameter.asType().toString();
        String parameterName = parameter.getSimpleName().toString();

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
                !declaredType.toString().startsWith("java.lang.") &&
                !declaredType.toString().startsWith("java.sql.")) {
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            String classType = declaredType.asElement().getSimpleName().toString();
            return addClassFieldsColumn(typeElement, classType, parameterName, null, parameter, console);
        }

        String[] fieldNames = fieldName != null
                ? new String[]{fieldName.value()}
                : where != null && where.value().length > 0
                ? where.value()
                : new String[]{parameterName};
        return addParamColumn(parameterType, parameterName, fieldNames, where, parameter, console);
    }

    private boolean addClassFieldsColumn(TypeElement classObj, String classType, String className, Where where, Element parameter, Messager console) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(classObj, fields);

        List<ColumnInfo> columns = new ArrayList<>();
        // List all fields in class
        for (VariableElement element : fields.values()) {
            String fieldType = element.asType().toString();
            String fieldName = element.getSimpleName().toString();

            ColumnInfo column = tableData.tableFields.get(fieldName);

            if (checkFieldTypeAndName(fieldType, fieldName, parameter, classObj, console, column))
                continue;

            // Skip field for query
            if (element.getAnnotation(Where.class) != null)
                continue;

            columns.add(column);
        }
        params.add(new MethodParamInfo(parameter, columns, classType, className, true, where));
        return false;
    }

    private boolean addParamColumn(String parameterType, String parameterName, String[] tableFieldNames, Where where,
                                   Element parameter, Messager console) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (String tableFieldName : tableFieldNames) {
            ColumnInfo column = tableData.tableFields.get(tableFieldName);
            if (checkFieldTypeAndName(parameterType, tableFieldName, parameter, null, console, column)) continue;

            // Check ignore case is used on string type only
            if (where != null && where.ignoreCase() && !parameterType.equals(String.class.getName())) {
                AnnotationMirror whereMirror = getAnnotationMirror(parameter, Where.class);
                console.printMessage(ERROR, "ignoreCase can only be used with String type parameter",
                        parameter, whereMirror, getAnnotationValue(whereMirror, "ignoreCase"));
                return true;
            }

            columns.add(column);
        }

        if (parameterType.startsWith("java.lang.")) parameterType = parameterType.substring(10);
        params.add(new MethodParamInfo(parameter, columns, parameterType, parameterName, false, where));
        return false;
    }

    private boolean checkFieldTypeAndName(String parameterType, String tableFieldName, Element parameter, TypeElement classObj, Messager console, ColumnInfo column) {
        if (classObj != null)
            tableFieldName = classObj.asType() + "::" + tableFieldName;

        // Check column in table
        if (column == null) {
            console.printMessage(ERROR, "Field '" + tableFieldName + "' not exist in table " +
                    tableData.tableInfo.classPath, parameter);
            return true;
        }
        // Check column type is same
        if (!column.field.asType().toString().equals(parameterType)) {
            console.printMessage(ERROR,
                    "Parameter type '" + parameterType + "' does not match field type '" + column.field.asType().toString() +
                            "' for column '" + column.columnName + "'", parameter);
            return true;
        }
        return false;
    }
}
