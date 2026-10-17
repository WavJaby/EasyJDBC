package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.annotation.*;
import com.wavjaby.jdbc.annotation.conf.Direction;
import com.wavjaby.jdbc.processor.EmptyProcessingException;
import org.jspecify.annotations.NonNull;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.stream.Collectors;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

/** One repository method: its annotations, parameters bound to columns, return shape, and the Operation they add up to. */
public class MethodInfo {
    private final TableData tableData;
    public final ExecutableElement method;

    public final TypeMirror returnTypeMirror;
    public final String methodName;
    public final List<MethodParamInfo> params;
    // Operation
    public final boolean modifyRow;
    public final boolean delete;
    public final boolean count;
    // Order by
    public final OrderBy[] orderBy;
    // Limit
    public final Integer limit;
    // Custom Query
    public final QuerySQL querySql;
    public final SqlParamInfo[] querySqlParams;

    // Throw exception when not found
    public final NotFoundException notFound;

    // Return type
    @NonNull
    public final ReturnInfo returns;

    // Insert method
    public final boolean batchInsert;
    public final boolean insertMethod;

    /** What the generated method does. Decided once here, so a method shape no operation accepts is a parse error and emit only switches. */
    public enum Operation {
        INSERT, UPDATE, UPDATE_CHECKED, DELETE, COUNT, EXISTS, SELECT_ROWS, SELECT_PROJECTION, SELECT_COLUMN
    }

    public final Operation operation;

    public MethodInfo(ExecutableElement method, TableData tableData, Types types, Messager console) throws EmptyProcessingException {
        this.method = method;
        this.tableData = tableData;
        this.methodName = method.getSimpleName().toString();
        this.returnTypeMirror = method.getReturnType();

        this.modifyRow = method.getAnnotation(Modifying.class) != null;
        this.delete = method.getAnnotation(Delete.class) != null;
        this.count = initCount(returnTypeMirror, method, console);

        this.orderBy = initOrderBy(method, tableData, console);

        Limit limit = method.getAnnotation(Limit.class);
        this.limit = limit == null ? null : limit.value();

        QuerySQL querySQL = method.getAnnotation(QuerySQL.class);
        Select select = method.getAnnotation(Select.class);

        // Parse custom SQL string
        CustomSql customSql = CustomSql.parse(select, querySQL, method, console);
        this.querySql = customSql.query() == null ? null : querySQL;

        // Get method parameters
        MethodParamParser parser = parseParamsToColumns(customSql, console);
        this.params = parser.params;
        this.batchInsert = parser.batchInsert;
        this.insertMethod = parser.insertMethod;

        this.querySqlParams = CustomSql.initSqlParams("querySql", customSql.query(), params, method, console);

        this.notFound = initNotFoundException(method);

        this.returns = ReturnInfo.parse(select, customSql, returnTypeMirror, tableData, params, method, types, console);

        this.operation = initOperation(console);
    }

    // First match wins, in this order: column SQL, batch insert, delete, primitive return (a @Select field reads that column), void return, row return, projection, single column
    private Operation initOperation(Messager console) throws EmptyProcessingException {
        if (returns.column().columnSqlParams() != null)
            return Operation.SELECT_COLUMN;

        // Batch insert is validated to return int, so never a list
        if (batchInsert)
            return Operation.INSERT;

        if (delete) {
            TypeKind kind = returnTypeMirror.getKind();
            if (kind == TypeKind.BOOLEAN || kind == TypeKind.INT || kind == TypeKind.VOID)
                return Operation.DELETE;
            console.printMessage(ERROR, "Unrecognized return type for delete method: " + method, method);
            throw new EmptyProcessingException();
        }

        if (returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
            TypeKind kind = primitiveReturnType.getKind();
            boolean selectsColumn = returns.column().column() != null;
            if (kind == TypeKind.BOOLEAN)
                return modifyRow ? Operation.UPDATE_CHECKED : selectsColumn ? Operation.SELECT_COLUMN : Operation.EXISTS;
            if (kind == TypeKind.INT && count)
                return Operation.COUNT;
            if (kind == TypeKind.INT && selectsColumn)
                return Operation.SELECT_COLUMN;
            console.printMessage(ERROR, "Unrecognized return type for " + returns.typeName() + " method: " + method, method);
            throw new EmptyProcessingException();
        }

        if (returnTypeMirror instanceof NoType) {
            if (modifyRow)
                return Operation.UPDATE;
            if (insertMethod)
                return Operation.INSERT;
            console.printMessage(ERROR, "Only insert, update method allow return void, method: " + method, method);
            throw new EmptyProcessingException();
        }

        if (returns.table()) {
            if (modifyRow || insertMethod) {
                // Both hand back the one row they wrote
                if (returns.list()) {
                    console.printMessage(ERROR, "Unsupported method return type: " + returnTypeMirror + ", for " + (modifyRow ? "update" : "insert") + " method", method);
                    throw new EmptyProcessingException();
                }
                return modifyRow ? Operation.UPDATE : Operation.INSERT;
            }
            return Operation.SELECT_ROWS;
        }

        if (returns.projection() != null) {
            if (modifyRow || insertMethod) {
                console.printMessage(ERROR, "Projection return '" + returns.typeName() + "' is for queries only; " + (modifyRow ? "update" : "insert") +
                        " returns the table class, a count or nothing", method);
                throw new EmptyProcessingException();
            }
            return Operation.SELECT_PROJECTION;
        }

        // ReturnInfo.parse only lets any other shape through with a @Select field or column SQL, and column SQL matched first
        return Operation.SELECT_COLUMN;
    }

    public record NotFoundException(TypeElement exception, String args) {

    }

    public static NotFoundException initNotFoundException(ExecutableElement method) {
        AnnotationMirror notFoundProcess = getAnnotationMirror(method, NotFound.class);
        if (notFoundProcess != null) {
            TypeElement exception = (TypeElement) getAnnotationValueClassElement(notFoundProcess, "exception");
            NotFound notFound = method.getAnnotation(NotFound.class);
            assert notFound != null;
            String args = notFound.args();
            return new NotFoundException(exception, args);
        }
        return null;
    }

    public record OrderBy(ColumnInfo column, Direction direction) {
    }

    private static OrderBy[] initOrderBy(ExecutableElement method, TableData tableData, Messager console) throws EmptyProcessingException {
        OrderBy[] result = null;
        Order order = method.getAnnotation(Order.class);
        Order.ByField[] orderByFields = order == null ? null : order.value();
        if (orderByFields != null) {
            boolean error = false;
            result = new OrderBy[orderByFields.length];
            for (int i = 0; i < orderByFields.length; i++) {
                String fieldName = orderByFields[i].value();
                if (fieldName.isBlank()) {
                    printError(console, method, Order.class, "field", "Field name can not be blank");
                    error = true;
                    continue;
                }
                ColumnInfo column = tableData.tableFields.get(fieldName);
                if (column == null) {
                    printError(console, method, Order.class, "field",
                            "Field name '" + fieldName + "' not exist in table " + tableData.tableInfo.classPath);
                    error = true;
                    continue;
                }
                result[i] = new OrderBy(column, orderByFields[i].direction());
            }
            if (error)
                throw new EmptyProcessingException();
        }
        return result;
    }

    private static boolean initCount(TypeMirror returnTypeMirror, ExecutableElement method, Messager console) throws EmptyProcessingException {
        // Check @Count method return type
        boolean count = method.getAnnotation(Count.class) != null;
        if (count && (!(returnTypeMirror instanceof PrimitiveType primitiveReturnType) || primitiveReturnType.getKind() != TypeKind.INT)) {
            printError(console, method, Count.class, null, "Count method must return int");
            throw new EmptyProcessingException();
        }
        return count;
    }

    private MethodParamParser parseParamsToColumns(CustomSql customSql, Messager console) throws EmptyProcessingException {
        List<? extends VariableElement> methodParams = method.getParameters();

        // Check if param is table class
        VariableElement selfTableParam = methodParams.stream()
                .filter(this::isElementTableClass)
                .findFirst().orElse(null);
        boolean insertMethod = selfTableParam != null;
        if (insertMethod) {
            // isElementTableClass will make sure it's a DeclaredType
            DeclaredType selfTableParamType = (DeclaredType) selfTableParam.asType();

            // Insert method only allow one parameter with table class
            if (methodParams.size() > 1) {
                String paramsStr = parmsToString(methodParams, selfTableParam);
                console.printMessage(ERROR, "Parameter with table class can not use with other parameters: '" + paramsStr + "'", method);
                throw new EmptyProcessingException();
            }
            // Check if param is List<TableClass>
            if (selfTableParamType.asElement().toString().equals(List.class.getName())) {
                if (!(this.returnTypeMirror instanceof PrimitiveType primitiveReturnType) ||
                        primitiveReturnType.getKind() != TypeKind.INT) {
                    console.printMessage(ERROR, "Return type must be int for batch insert method", method);
                    throw new EmptyProcessingException();
                }
                DeclaredType genericSuperType = (DeclaredType) selfTableParamType.getTypeArguments().get(0);
                return new MethodParamParser(genericSuperType.asElement(), methodParams.get(0).getSimpleName().toString(), tableData, customSql, console);
            }
        }

        return new MethodParamParser(methodParams, tableData, customSql, insertMethod, console);
    }

    private boolean isElementTableClass(VariableElement parameter) {
        if (parameter.asType() instanceof DeclaredType declaredType) {
            // Check if declaredType is List
            if (declaredType.asElement().toString().equals(List.class.getName())) {
                // Check if generic type is table class
                if (!declaredType.getTypeArguments().isEmpty() &&
                        declaredType.getTypeArguments().get(0) instanceof DeclaredType genericType) {
                    return genericType.toString().equals(tableData.tableInfo.classPath);
                }
                return false;
            }

            return declaredType.toString().equals(tableData.tableInfo.classPath);
        }
        return false;
    }

    private String parmsToString(List<? extends VariableElement> params, VariableElement excludedParam) {
        return params.stream()
                .filter(parameter -> parameter != excludedParam)
                .map(VariableElement::getSimpleName)
                .collect(Collectors.joining(","));
    }
}
