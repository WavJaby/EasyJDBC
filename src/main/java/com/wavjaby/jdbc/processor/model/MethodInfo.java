package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.processor.EmptyProcessingException;
import com.wavjaby.jdbc.processor.util.MethodParamParser;
import com.wavjaby.persistence.*;
import com.wavjaby.persistence.conf.Direction;
import org.jspecify.annotations.NonNull;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
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
    public ReturnInfo returns;

    // Insert method
    public final boolean batchInsert;
    public final boolean insertMethod;

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

    public record ReturnInfo(
            @NonNull
            ReturnColumn column,
            boolean table,
            boolean list,
            String typeName) {

    }

    private static ReturnInfo initReturnInfo(Select select, CustomSqlRaw customSql, TypeMirror returnTypeMirror, TableData tableData, List<MethodParamInfo> params, ExecutableElement method, Messager console) throws EmptyProcessingException {
        ReturnColumn returnColumn = initReturnColumn(select, customSql.column, tableData, params, method, console);

        if (returnTypeMirror instanceof DeclaredType declaredReturnType) {
            TypeElement returnTypeElement = (TypeElement) declaredReturnType.asElement();
            String returnTypeClassPath = declaredReturnType.asElement().toString();
            // Check if return table itself or subclass of table class
            if (returnTypeClassPath.equals(tableData.tableInfo.classPath) ||
                    checkInstanceof(tableData.tableInfo.tableClassEle, returnTypeElement)) {
                if (!returnColumn.isNull()) {
                    printError(console, method, Select.class, "Return with table class must not use @Select");
                    throw new EmptyProcessingException();
                }

                return new ReturnInfo(returnColumn, true, false, tableData.tableInfo.className);
            }

            // Check if return type is List
            if (returnTypeClassPath.equals(List.class.getName())) {
                TypeMirror genericSuperType = declaredReturnType.getTypeArguments().get(0);
                // Check generic type is valid
                if (genericSuperType.toString().equals(tableData.tableInfo.classPath)) {
                    if (!returnColumn.isNull()) {
                        printError(console, method, Select.class, "Return with table class must not use @Select");
                        throw new EmptyProcessingException();
                    }
                    return new ReturnInfo(returnColumn, true, true, tableData.tableInfo.className);
                } else if (!returnColumn.isNull()) {
                    return new ReturnInfo(returnColumn, false, true, getTypeName(genericSuperType));
                }
            }
            // Check if return column
            else if (!returnColumn.isNull()) {
                return new ReturnInfo(returnColumn, false, false, getTypeName(returnTypeMirror));
            }
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
