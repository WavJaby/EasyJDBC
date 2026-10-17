package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.Select;
import com.wavjaby.jdbc.processor.EmptyProcessingException;
import org.jspecify.annotations.NonNull;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static com.wavjaby.jdbc.processor.model.MethodParamParser.getTypeName;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.WARNING;

/** What a repository method returns, per row: the table, a projection record, or one column named by @Select. */
public record ReturnInfo(
        @NonNull
        ReturnColumn column,
        boolean table,
        boolean list,
        String typeName,
        Projection projection) {

    ReturnInfo(ReturnColumn column, boolean table, boolean list, String typeName) {
        this(column, table, list, typeName, null);
    }

    public record ReturnColumn(ColumnInfo column, SqlParamInfo[] columnSqlParams) {
        public boolean isNull() {
            return column == null && columnSqlParams == null;
        }
    }

    private static ReturnColumn initReturnColumn(Select select, List<CustomSql.SqlParam> column, TableData tableData, List<MethodParamInfo> params, ExecutableElement method, Messager console) throws EmptyProcessingException {
        if (select == null)
            return new ReturnColumn(null, null);

        if (column == null && select.field().trim().isEmpty()) {
            AnnotationMirror mirror = getAnnotationMirror(method, Select.class);
            console.printMessage(WARNING, "Column select is empty", method, mirror);
            return new ReturnColumn(null, null);
        }

        String returnField = select.field().trim().isEmpty() ? null : select.field().trim();

        // Print error if both return field and sql are used
        if (column != null && returnField != null) {
            printError(console, method, Select.class, "field",
                    "Return field and column SQL can not be used together");
            throw new EmptyProcessingException();
        }

        if (column != null) {
            SqlParamInfo[] returnColumnSqlParams = CustomSql.initSqlParams("columnSql", column, params, method, console);
            return new ReturnColumn(null, returnColumnSqlParams);
        } else if (returnField != null) {
            ColumnInfo returnColumn = tableData.tableFields.get(returnField);
            if (returnColumn == null) {
                printError(console, method, Select.class, "field",
                        "Return field '" + returnField + "' not exist in table " + tableData.tableInfo.classPath);
                throw new EmptyProcessingException();
            }
            return new ReturnColumn(returnColumn, null);
        } else
            return new ReturnColumn(null, null);
    }

    /** A record returned instead of the table: one column per component, in component order. */
    public record Projection(TypeElement type, List<ColumnInfo> columns) {
    }


    /**
     * A declared return type is, per row (the List element when it is a List): the table class, a projection record, or a single-column type that names its column with @Select.
     * A supertype of the table is refused: the generated List of the table class is not assignable to a List of the supertype.
     */
    static ReturnInfo parse(Select select, CustomSql customSql, TypeMirror returnTypeMirror, TableData tableData, List<MethodParamInfo> params, ExecutableElement method, Types types, Messager console) throws EmptyProcessingException {
        ReturnColumn returnColumn = initReturnColumn(select, customSql.column(), tableData, params, method, console);

        if (returnTypeMirror instanceof DeclaredType declaredReturnType) {
            boolean list = declaredReturnType.asElement().toString().equals(List.class.getName());
            TypeMirror rowType = list ? declaredReturnType.getTypeArguments().get(0) : returnTypeMirror;
            TypeElement rowElement = rowType instanceof DeclaredType declaredRowType ? (TypeElement) declaredRowType.asElement() : null;

            if (rowElement != null && rowElement.toString().equals(tableData.tableInfo.classPath)) {
                if (!returnColumn.isNull()) {
                    printError(console, method, Select.class, "Return with table class must not use @Select");
                    throw new EmptyProcessingException();
                }
                return new ReturnInfo(returnColumn, true, list, tableData.tableInfo.className);
            }

            if (rowElement != null && checkInstanceof(tableData.tableInfo.tableClassEle, rowElement)) {
                console.printMessage(ERROR, "Return type '" + rowType + "' is a supertype of table class '" + tableData.tableInfo.classPath +
                        "': return the table class itself, or a projection record of the columns needed", method);
                throw new EmptyProcessingException();
            }

            if (rowElement != null && rowElement.getKind() == ElementKind.RECORD && !rowElement.toString().startsWith("java.")) {
                if (!returnColumn.isNull()) {
                    printError(console, method, Select.class, "Projection record '" + rowElement + "' selects the columns its components name: remove @Select");
                    throw new EmptyProcessingException();
                }
                Projection projection = initProjection(rowElement, tableData, method, types, console);
                return new ReturnInfo(returnColumn, false, list, getTypeName(rowType), projection);
            }

            if (returnColumn.isNull()) {
                console.printMessage(ERROR, "Return type '" + rowType + "' is a single column: name it with @Select(field = ...) or @Select(columnSql = ...); " +
                        "to return rows, return the table class or a projection record", method);
                throw new EmptyProcessingException();
            }
            checkColumnReturnType(returnColumn, rowType, returnTypeMirror, method, types, console);
            return new ReturnInfo(returnColumn, false, list, getTypeName(rowType));
        } else if (returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
            if (primitiveReturnType.getKind() == TypeKind.BOOLEAN || primitiveReturnType.getKind() == TypeKind.INT) {
                checkColumnReturnType(returnColumn, returnTypeMirror, returnTypeMirror, method, types, console);
                return new ReturnInfo(returnColumn, false, false, primitiveReturnType.getKind() == TypeKind.INT ? "int" : "boolean");
            }
        } else if (returnTypeMirror instanceof ArrayType arrayType) {
            if (!(arrayType.getComponentType() instanceof DeclaredType) && arrayType.getComponentType().getKind() != TypeKind.BYTE) {
                console.printMessage(ERROR, "Primitive array is not supported: " + arrayType + ", use List or DeclaredType array instead.", method);
                throw new EmptyProcessingException();
            }

            // Only allow when return field is used
            if (!returnColumn.isNull()) {
                checkColumnReturnType(returnColumn, returnTypeMirror, returnTypeMirror, method, types, console);
                return new ReturnInfo(returnColumn, false, false, getTypeName(returnTypeMirror));
            } else {
                console.printMessage(ERROR, "Return with array type must use returnField, to query multiple objects, use List<>", method);
                throw new EmptyProcessingException();
            }
        } else if (returnTypeMirror instanceof NoType) {
            return new ReturnInfo(returnColumn, false, false, "void");
        }

        console.printMessage(ERROR, "Repository '" + tableData.tableInfo.repoIntPackagePath +
                "' for table class '" + tableData.tableInfo.classPath +
                "', method: '" + method.toString() + "' return type '" + returnTypeMirror + "' is not acceptable", method);
        throw new EmptyProcessingException();
    }

    /**
     * Every component must be a table field of the same type -- its own name, or the one its @FieldName gives. No SQL alias is needed for a rename:
     * the row mapper fills constructor parameters by position, so SELECT only has to follow component order.
     */
    private static Projection initProjection(TypeElement record, TableData tableData, ExecutableElement method, Types types, Messager console) throws EmptyProcessingException {
        List<? extends RecordComponentElement> components = record.getRecordComponents();
        List<ColumnInfo> columns = new ArrayList<>(components.size());
        boolean error = false;
        for (RecordComponentElement component : components) {
            String name = component.getSimpleName().toString();
            String fieldName = name;
            // @FieldName targets FIELD, so on a component javac hands it to the record's private field, not to the component element
            VariableElement field = recordField(record, name);
            AnnotationMirror alias = field == null ? null : getAnnotationMirror(field, FieldName.class);
            if (alias != null) {
                if (getAnnotationValueClass(alias, "referencedClass") != null) {
                    console.printMessage(ERROR, "Projection '" + record + "' component '" + name + "': @FieldName(referencedClass) picks a joined table in a view; " +
                            "a projection reads only '" + tableData.tableInfo.classPath + "'", method);
                    error = true;
                    continue;
                }
                String value = field.getAnnotation(FieldName.class).value().trim();
                if (!value.isEmpty())
                    fieldName = value;
            }
            ColumnInfo column = tableData.tableFields.get(fieldName);
            if (column == null) {
                console.printMessage(ERROR, "Projection '" + record + "' component '" + name + "'" + (fieldName.equals(name) ? "" : " (@FieldName '" + fieldName + "')") +
                        " is not a field of table '" + tableData.tableInfo.classPath + "'", method);
                error = true;
                continue;
            }
            if (!types.isSameType(component.asType(), column.field.asType())) {
                console.printMessage(ERROR, "Projection '" + record + "' component '" + name + "' is '" + component.asType() +
                        "' but the field is '" + column.field.asType() + "' in table '" + tableData.tableInfo.classPath + "'", method);
                error = true;
                continue;
            }
            columns.add(column);
        }

        // The canonical constructor follows component order; what can still go wrong is a second constructor at its arity
        try {
            RowConstructor.check(record, components.stream().map(c -> c.getSimpleName().toString()).toList(),
                    components.stream().map(Element::asType).toList(), "Projection '" + record + "'", method, types, console);
        } catch (EmptyProcessingException e) {
            error = true;
        }

        if (error)
            throw new EmptyProcessingException();
        return new Projection(record, columns);
    }

    // A @Select(field) column reads as the field's type. The List form is ambiguous on purpose: per row (List<String> of a String field) or the field itself (List<String> of a List<String> field)
    private static void checkColumnReturnType(ReturnColumn returnColumn, TypeMirror rowType, TypeMirror returnType, ExecutableElement method, Types types, Messager console) throws EmptyProcessingException {
        if (returnColumn.column() == null)
            return; // columnSql: its type is whatever the SQL computes
        TypeMirror fieldType = boxed(types, returnColumn.column().field.asType());
        if (types.isSameType(boxed(types, rowType), fieldType) || types.isSameType(boxed(types, returnType), fieldType))
            return;
        AnnotationMirror mirror = getAnnotationMirror(method, Select.class);
        console.printMessage(ERROR, "Return type '" + rowType + "' does not match field '" + returnColumn.column().field.getSimpleName() +
                "' of type '" + returnColumn.column().field.asType() + "'", method, mirror, getAnnotationValue(mirror, "field"));
        throw new EmptyProcessingException();
    }

    private static VariableElement recordField(TypeElement record, String name) {
        for (Element member : record.getEnclosedElements())
            if (member.getKind() == ElementKind.FIELD && member.getSimpleName().contentEquals(name))
                return (VariableElement) member;
        return null;
    }

    private static TypeMirror boxed(Types types, TypeMirror type) {
        return type instanceof PrimitiveType primitive ? types.boxedClass(primitive).asType() : type;
    }
}
