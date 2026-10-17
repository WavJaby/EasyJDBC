package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.processor.EmptyProcessingException;
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
    private final CustomSql customSql;
    private final Messager console;

    public final boolean batchInsert;
    public final boolean insertMethod;
    public final List<MethodParamInfo> params = new ArrayList<>();

    public MethodParamParser(Element parameter, String parameterName, TableData tableData, CustomSql customSql, Messager console) throws EmptyProcessingException {
        this.tableData = tableData;
        this.customSql = customSql;
        this.console = console;
        this.batchInsert = true;
        this.insertMethod = true;

        addElement(parameter, parameterName);
    }

    public MethodParamParser(List<? extends VariableElement> methodParams, TableData tableData, CustomSql customSql, boolean insertMethod, Messager console) throws EmptyProcessingException {
        this.tableData = tableData;
        this.customSql = customSql;
        this.console = console;

        this.batchInsert = false;
        this.insertMethod = insertMethod;

        // Every parameter is checked so each bad one reports
        boolean error = false;
        for (VariableElement parameter : methodParams) {
            try {
                addElement(parameter, parameter.getSimpleName().toString());
            } catch (EmptyProcessingException e) {
                error = true;
            }
        }
        if (error)
            throw new EmptyProcessingException();
    }

    private void addElement(Element parameter, String parameterName) throws EmptyProcessingException {
        TypeMirror parameterType = parameter.asType();

        // Custom param name
        FieldName fieldName = parameter.getAnnotation(FieldName.class);
        Where where = parameter.getAnnotation(Where.class);
        UpdateData updateData = parameter.getAnnotation(UpdateData.class);

        if (updateData != null && (fieldName != null || where != null)) {
            console.printMessage(ERROR, "@UpdateData can not use with @FieldName or @Where annotation", parameter, getAnnotationMirror(parameter, UpdateData.class));
            throw new EmptyProcessingException();
        }
        if (fieldName != null && where != null) {
            console.printMessage(ERROR, "@Where can not use with @FieldName annotation", parameter, getAnnotationMirror(parameter, Where.class));
            throw new EmptyProcessingException();
        }

        // Process class data
        if ((parameter.asType() instanceof DeclaredType declaredType) &&
                !declaredType.asElement().getKind().equals(ElementKind.ENUM) &&
                !declaredType.toString().startsWith("java.")) {
            TypeElement typeElement = (TypeElement) declaredType.asElement();
            addClassFieldsColumn(typeElement, parameterName, parameter);
            return;
        }

        String columnFieldName = fieldName != null && !fieldName.value().trim().isEmpty()
                ? fieldName.value().trim() : parameterName;
        addParamColumn(parameterType, parameterName, columnFieldName, where, parameter);
    }

    private void addClassFieldsColumn(TypeElement classType, String paramName, Element parameter) throws EmptyProcessingException {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(classType, fields);

        List<ColumnInfo> columns = new ArrayList<>();
        // List all fields in class
        boolean error = false;
        for (VariableElement element : fields.values()) {
            String fieldName = element.getSimpleName().toString();

            ColumnInfo column = tableData.tableFields.get(fieldName);

            try {
                requireColumn(fieldName, parameter, paramName, classType, column);
            } catch (EmptyProcessingException e) {
                error = true;
                continue;
            }

            // Skip field for query
            if (element.getAnnotation(Where.class) != null)
                continue;

            columns.add(column);
        }
        params.add(new MethodParamInfo(parameter, columns, getTypeName(classType.asType()), paramName, true, null, false));
        if (error)
            throw new EmptyProcessingException();
    }

    private void addParamColumn(TypeMirror parameterType, String paramName, String columnFieldName, Where where, Element parameter) throws EmptyProcessingException {
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

            try {
                requireColumn(fieldName, parameter, paramName, null, column);
            } catch (EmptyProcessingException e) {
                error = true;
                continue;
            }

            // Check ignore case is used on string type only
            if (where != null && where.ignoreCase() && !parameterType.toString().equals(String.class.getName())) {
                AnnotationMirror whereMirror = getAnnotationMirror(parameter, Where.class);
                console.printMessage(ERROR, "ignoreCase can only be used with String type parameter",
                        parameter, whereMirror, getAnnotationValue(whereMirror, "ignoreCase"));
                error = true;
            }

            columns.add(column);
        }
        if (error)
            throw new EmptyProcessingException();

        params.add(new MethodParamInfo(parameter, columns, getTypeName(parameterType), paramName, false, where, customSqlParam));
    }

    // Existence only: tableFields is keyed by field name, so a found column always has this name, and parameter types are not compared
    private void requireColumn(String fieldName, Element parameter, String paramName, TypeElement classType, ColumnInfo column) throws EmptyProcessingException {
        if (column != null)
            return;
        String inClass = classType != null ? "(in class: '" + classType.asType().toString() + "', parameter: '" + paramName + "')" : "";
        console.printMessage(ERROR, "Field '" + fieldName + "'" + inClass + " does not exist in table '" + tableData.tableInfo.classPath + "'", parameter);
        throw new EmptyProcessingException();
    }

    public static String getTypeName(TypeMirror typeMirror) {
        String typeStr = typeMirror.toString();
        if (typeStr.startsWith("java.lang.")) return typeStr.substring(10);
        return typeStr;
    }
}
