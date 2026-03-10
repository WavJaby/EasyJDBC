package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.processor.EmptyProcessingException;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.MethodInfo;
import com.wavjaby.jdbc.processor.model.MethodParamInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.UpdateData;
import com.wavjaby.jdbc.annotation.Where;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

public class MethodParamParser {
    private final TableData tableData;
    private final MethodInfo.CustomSqlRaw customSql;
    private final Messager console;

    public final boolean batchInsert;
    public final boolean insertMethod;
    public final List<MethodParamInfo> params = new ArrayList<>();

    public MethodParamParser(Element parameter, String parameterName, TableData tableData, MethodInfo.CustomSqlRaw customSql, Messager console) throws EmptyProcessingException {
        this.tableData = tableData;
        this.customSql = customSql;
        this.console = console;
        this.batchInsert = true;
        this.insertMethod = true;

        if (addElement(parameter, parameterName))
            throw new EmptyProcessingException();
    }

    public MethodParamParser(List<? extends VariableElement> methodParams, TableData tableData, MethodInfo.CustomSqlRaw customSql, boolean insertMethod, Messager console) throws EmptyProcessingException {
        this.tableData = tableData;
        this.customSql = customSql;
        this.console = console;

        this.batchInsert = false;
        this.insertMethod = insertMethod;

        boolean error = false;
        for (VariableElement parameter : methodParams) {
            String parameterName = parameter.getSimpleName().toString();
            if (addElement(parameter, parameterName))
                error = true;
        }
        if (error)
            throw new EmptyProcessingException();
    }

    private boolean addElement(Element parameter, String parameterName) {
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
            return addClassFieldsColumn(typeElement, parameterName, parameter);
        }

        String columnFieldName = fieldName != null && !fieldName.value().trim().isEmpty()
                ? fieldName.value().trim() : parameterName;
        return addParamColumn(parameterType, parameterName, columnFieldName, where, parameter);
    }

    private boolean addClassFieldsColumn(TypeElement classType, String paramName, Element parameter) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(classType, fields);

        List<ColumnInfo> columns = new ArrayList<>();
        // List all fields in class
        boolean error = false;
        for (VariableElement element : fields.values()) {
            String fieldType = element.asType().toString();
            String fieldName = element.getSimpleName().toString();

            ColumnInfo column = tableData.tableFields.get(fieldName);

            if (checkFieldTypeAndName(fieldType, fieldName, parameter, paramName, classType, column, console)) {
                error = true;
                continue;
            }

            // Skip field for query
            if (element.getAnnotation(Where.class) != null)
                continue;

            columns.add(column);
        }
        MethodParamInfo methodParamInfo = new MethodParamInfo(parameter, columns, getTypeName(classType.asType()), paramName, true, null, false);
        addParamAndCheckConflict(methodParamInfo);
        return error;
    }

    private boolean addParamColumn(TypeMirror parameterType, String paramName, String columnFieldName, Where where, Element parameter) {
        List<ColumnInfo> columns = new ArrayList<>();

        boolean error = false;
        String[] columnFieldNames = where != null && where.value().length > 0
                ? where.value()
                : new String[]{columnFieldName};

        // Check if method parameter is for SQL parameter
        boolean customSqlParam = customSql.isParamExist(paramName);

        for (String fieldName : columnFieldNames) {
            ColumnInfo column = tableData.tableFields.get(fieldName);
            if (column == null && customSqlParam)
                continue;

            if (checkFieldTypeAndName(parameterType.toString(), fieldName, parameter, paramName, null, column, console)) {
                error = true;
                continue;
            }
//            if (!customSqlParam)
//                customSqlParam = customSql.isParamExist(fieldName);

            // Check ignore case is used on string type only
            if (where != null && where.ignoreCase() && !parameterType.toString().equals(String.class.getName())) {
                AnnotationMirror whereMirror = getAnnotationMirror(parameter, Where.class);
                console.printMessage(ERROR, "ignoreCase can only be used with String type parameter",
                        parameter, whereMirror, getAnnotationValue(whereMirror, "ignoreCase"));
                error = true;
            }

            columns.add(column);
        }
        if (error) return true;


        MethodParamInfo methodParamInfo = new MethodParamInfo(parameter, columns, getTypeName(parameterType), paramName, false, where, customSqlParam);

        return addParamAndCheckConflict(methodParamInfo);
    }

    private boolean addParamAndCheckConflict(MethodParamInfo methodParamInfo) {
        params.add(methodParamInfo);
        return false;
    }

    private boolean checkFieldTypeAndName(String parameterType, String fieldName, Element parameter, String paramName, TypeElement classType, ColumnInfo column, Messager console) {
//        if (classType != null)
//            fieldName = classType.asType() + "::" + fieldName;

        // field for column exist and type is same
        if (column != null && column.field.getSimpleName().toString().equals(fieldName)) {
            return false;
        }

        String inClass = classType != null ? "(in class: '" + classType.asType().toString() + "', parameter: '" + paramName + "')" : "";
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

    public static String getTypeName(TypeMirror typeMirror) {
        String typeStr = typeMirror.toString();
        if (typeStr.startsWith("java.lang.")) return typeStr.substring(10);
        return typeStr;
    }
}
