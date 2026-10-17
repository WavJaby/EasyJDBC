package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.annotation.*;
import com.wavjaby.jdbc.annotation.conf.Direction;
import com.wavjaby.jdbc.processor.EmptyProcessingException;
import com.wavjaby.jdbc.processor.TableProcessor;
import com.wavjaby.jdbc.processor.util.MethodParamParser;
import org.jspecify.annotations.NonNull;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static com.wavjaby.jdbc.processor.util.MethodParamParser.getTypeName;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.WARNING;

public class MethodInfo {
    private final static Pattern paramPattern = Pattern.compile(":([a-zA-Z_]\\w*)(\\.\\w+)*");
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

    public MethodInfo(ExecutableElement method, TableData tableData, Messager console) throws EmptyProcessingException {
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
        CustomSqlRaw customSql = parseCustomSql(select, querySQL, method, console);
        this.querySql = customSql.query == null ? null : querySQL;

        // Get method parameters
        MethodParamParser parser = parseParamsToColumns(customSql, console);
        this.params = parser.params;
        this.batchInsert = parser.batchInsert;
        this.insertMethod = parser.insertMethod;

        this.querySqlParams = initSqlParams("querySql", customSql.query, params, method, console);

        this.notFound = initNotFoundException(method);

        this.returns = initReturnInfo(select, customSql, returnTypeMirror, tableData, params, method, console);

        this.operation = initOperation(console);
    }

    // First match wins, in this order: column SQL, batch insert, delete, primitive return, void return, row return, projection, single column
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
            if (kind == TypeKind.BOOLEAN)
                return modifyRow ? Operation.UPDATE_CHECKED : Operation.EXISTS;
            if (kind == TypeKind.INT && count)
                return Operation.COUNT;
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

        // initReturnInfo only lets any other shape through with a @Select field or column SQL, and column SQL matched first
        return Operation.SELECT_COLUMN;
    }

    public record CustomSqlRaw(List<SqlParam> column, List<SqlParam> query) {
        public record SqlParam(String sql, String paramName) {
        }

        public boolean isParamExist(String paramName) {
            if (query != null && query.stream().anyMatch(param -> paramName.equals(param.paramName)))
                return true;
            if (column != null && column.stream().anyMatch(param -> paramName.equals(param.paramName)))
                return true;
            return false;
        }
    }

    private static CustomSqlRaw parseCustomSql(Select select, QuerySQL querySQL, ExecutableElement method, Messager console) throws EmptyProcessingException {
        List<CustomSqlRaw.SqlParam> column = null, query = null;

        if (select != null) {
            if (select.columnSql().trim().isEmpty() && select.field().trim().isEmpty()) {
                AnnotationMirror mirror = getAnnotationMirror(method, Select.class);
                console.printMessage(WARNING, "Column select is empty", method, mirror);
            } else if (!select.columnSql().trim().isEmpty()) {
                column = parseSqlParam(select.columnSql());
            }
        }

        if (querySQL != null) {
            if (querySQL.value().trim().isEmpty()) {
                AnnotationMirror mirror = getAnnotationMirror(method, QuerySQL.class);
                assert mirror != null;
                AnnotationValue value = getAnnotationValue(mirror, "value");
                console.printMessage(WARNING, "Query SQL is empty", method, mirror, value);
            }
            query = parseSqlParam(querySQL.value());
        }


        return new CustomSqlRaw(column, query);
    }

    private static SqlParamInfo[] initSqlParams(String keyName, List<CustomSqlRaw.SqlParam> sqlParams, List<MethodParamInfo> methodParams, ExecutableElement method, Messager console) throws EmptyProcessingException {
        if (sqlParams == null || sqlParams.isEmpty())
            return null;

        Map<String, MethodParamInfo> paramMap = new HashMap<>(sqlParams.size());
        for (MethodParamInfo param : methodParams) {
            if (param.customSqlParam) {
                paramMap.put(param.paramName, param);
            }
        }

        SqlParamInfo[] result = new SqlParamInfo[sqlParams.size()];
        result[result.length - 1] = new SqlParamInfo(sqlParams.get(sqlParams.size() - 1).sql(), null, null);

        boolean error = false;
        for (int i = 0; i < sqlParams.size() - 1; i++) {
            MethodParamInfo param = paramMap.get(sqlParams.get(i).paramName());
            if (param == null) {
                error = true;
                printError(console, method, Select.class, keyName, "Parameter '" + sqlParams.get(i).paramName() + "' is not defined in method parameters");
            } else if (param.isEnum && param.columns.size() > 1) {
                error = true;
                printError(console, method, Select.class, keyName, "Parameter '" + sqlParams.get(i).paramName() + "' have multiple columns, only one column is allowed in custom SQL");
            }
            result[i] = new SqlParamInfo(sqlParams.get(i).sql(), sqlParams.get(i).paramName(), param);
        }
        if (error)
            throw new EmptyProcessingException();

        return result;
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

    public record ReturnColumn(ColumnInfo column, SqlParamInfo[] columnSqlParams) {
        public boolean isNull() {
            return column == null && columnSqlParams == null;
        }
    }

    private static ReturnColumn initReturnColumn(Select select, List<CustomSqlRaw.SqlParam> column, TableData tableData, List<MethodParamInfo> params, ExecutableElement method, Messager console) throws EmptyProcessingException {
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
            SqlParamInfo[] returnColumnSqlParams = initSqlParams("columnSql", column, params, method, console);
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
    }

    /**
     * A declared return type is, per row (the List element when it is a List): the table class, a projection record, or a single-column type that names its column with @Select.
     * A supertype of the table is refused: the generated List of the table class is not assignable to a List of the supertype.
     */
    private static ReturnInfo initReturnInfo(Select select, CustomSqlRaw customSql, TypeMirror returnTypeMirror, TableData tableData, List<MethodParamInfo> params, ExecutableElement method, Messager console) throws EmptyProcessingException {
        ReturnColumn returnColumn = initReturnColumn(select, customSql.column, tableData, params, method, console);

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
                Projection projection = initProjection(rowElement, tableData, method, console);
                return new ReturnInfo(returnColumn, false, list, getTypeName(rowType), projection);
            }

            if (returnColumn.isNull()) {
                console.printMessage(ERROR, "Return type '" + rowType + "' is a single column: name it with @Select(field = ...) or @Select(columnSql = ...); " +
                        "to return rows, return the table class or a projection record", method);
                throw new EmptyProcessingException();
            }
            checkColumnReturnType(returnColumn, rowType, returnTypeMirror, method, console);
            return new ReturnInfo(returnColumn, false, list, getTypeName(rowType));
        } else if (returnTypeMirror instanceof PrimitiveType primitiveReturnType) {
            if (primitiveReturnType.getKind() == TypeKind.BOOLEAN) {
                return new ReturnInfo(returnColumn, false, false, "boolean");
            }
            if (primitiveReturnType.getKind() == TypeKind.INT) {
                return new ReturnInfo(returnColumn, false, false, "int");
            }
        } else if (returnTypeMirror instanceof ArrayType arrayType) {
            if (!(arrayType.getComponentType() instanceof DeclaredType) && arrayType.getComponentType().getKind() != TypeKind.BYTE) {
                console.printMessage(ERROR, "Primitive array is not supported: " + arrayType + ", use List or DeclaredType array instead.", method);
                throw new EmptyProcessingException();
            }

            // Only allow when return field is used
            if (!returnColumn.isNull()) {
                checkColumnReturnType(returnColumn, returnTypeMirror, returnTypeMirror, method, console);
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

    // Every component must be a table field of the same type; the row mapper fills constructor parameters by position, so SELECT follows component order
    private static Projection initProjection(TypeElement record, TableData tableData, ExecutableElement method, Messager console) throws EmptyProcessingException {
        Types types = TableProcessor.getTypeUtils();
        List<? extends RecordComponentElement> components = record.getRecordComponents();
        List<ColumnInfo> columns = new ArrayList<>(components.size());
        boolean error = false;
        for (RecordComponentElement component : components) {
            String name = component.getSimpleName().toString();
            ColumnInfo column = tableData.tableFields.get(name);
            if (column == null) {
                console.printMessage(ERROR, "Projection '" + record + "' component '" + name + "' is not a field of table '" + tableData.tableInfo.classPath + "'", method);
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

        // FastRowMapper takes the first public constructor whose arity equals the field count, and getConstructors() has no defined order
        long sameArity = record.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR && e.getModifiers().contains(Modifier.PUBLIC))
                .filter(e -> ((ExecutableElement) e).getParameters().size() == components.size())
                .count();
        if (sameArity > 1) {
            console.printMessage(ERROR, "Projection '" + record + "' has " + sameArity + " public constructors taking " + components.size() +
                    " parameters; the row mapper would pick one arbitrarily -- keep only the canonical constructor at that arity", method);
            error = true;
        }

        if (error)
            throw new EmptyProcessingException();
        return new Projection(record, columns);
    }

    // A @Select(field) column reads as the field's type. The List form is ambiguous on purpose: per row (List<String> of a String field) or the field itself (List<String> of a List<String> field)
    private static void checkColumnReturnType(ReturnColumn returnColumn, TypeMirror rowType, TypeMirror returnType, ExecutableElement method, Messager console) throws EmptyProcessingException {
        if (returnColumn.column() == null)
            return; // columnSql: its type is whatever the SQL computes
        Types types = TableProcessor.getTypeUtils();
        TypeMirror fieldType = boxed(types, returnColumn.column().field.asType());
        if (types.isSameType(boxed(types, rowType), fieldType) || types.isSameType(boxed(types, returnType), fieldType))
            return;
        AnnotationMirror mirror = getAnnotationMirror(method, Select.class);
        console.printMessage(ERROR, "Return type '" + rowType + "' does not match field '" + returnColumn.column().field.getSimpleName() +
                "' of type '" + returnColumn.column().field.asType() + "'", method, mirror, getAnnotationValue(mirror, "field"));
        throw new EmptyProcessingException();
    }

    private static TypeMirror boxed(Types types, TypeMirror type) {
        return type instanceof PrimitiveType primitive ? types.boxedClass(primitive).asType() : type;
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

    private static List<CustomSqlRaw.SqlParam> parseSqlParam(String sql) {
        String returnColumnSql = sql.replaceAll(" *\r?\n *", " ");
        Matcher matcher = paramPattern.matcher(returnColumnSql);
        int index = 0;
        List<CustomSqlRaw.SqlParam> result = new ArrayList<>();
        while (matcher.find()) {
            String pramName = matcher.group().substring(1);

            result.add(new CustomSqlRaw.SqlParam(returnColumnSql.substring(index, matcher.start()), pramName));
            index = matcher.end();
        }
        result.add(new CustomSqlRaw.SqlParam(returnColumnSql.substring(index), null));
        return result;
    }

    private MethodParamParser parseParamsToColumns(MethodInfo.CustomSqlRaw customSql, Messager console) throws EmptyProcessingException {
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
