package com.wavjaby.jdbc.processor.emit;


import com.squareup.javapoet.*;
import com.wavjaby.jdbc.processor.model.*;
import org.springframework.jdbc.core.SqlParameterValue;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.wavjaby.jdbc.processor.emit.PostgresDialect.enumBindCast;
import static com.wavjaby.jdbc.processor.emit.PostgresDialect.quoteColumn;

public class JdbcCodeGenerator {

    public record QueryAndArgs(StringBuilder query, List<CodeBlock> args) {
    }

    /** The clause a parameter list renders as: its keyword, the separator between parameters and how one column reads. */
    private enum Clause {
        WHERE("WHERE ", " AND "), INSERT(null, ","), SET("SET ", ",");

        final String keyword, separator;

        Clause(String keyword, String separator) {
            this.keyword = keyword;
            this.separator = separator;
        }
    }

    /** " WHERE a=? AND (b=? OR c=?)" plus the method's @QuerySQL, if {@code methodInfo} is given and has one; "" with neither. */
    public static QueryAndArgs where(List<MethodParamInfo> params, MethodInfo methodInfo) {
        return clause(Clause.WHERE, params, methodInfo);
    }

    /** " (a,b)VALUES(?,?)" -- the column list from {@code params} (with generated ids), the placeholders from the method's own parameters. */
    public static QueryAndArgs insert(List<MethodParamInfo> params, MethodInfo methodInfo) {
        return clause(Clause.INSERT, params, methodInfo);
    }

    /** " SET a=?,b=?" followed by the WHERE of {@code whereParams} and the method's @QuerySQL. */
    public static QueryAndArgs update(List<MethodParamInfo> whereParams, List<MethodParamInfo> setParams, MethodInfo methodInfo) {
        QueryAndArgs where = where(whereParams, methodInfo);
        QueryAndArgs values = clause(Clause.SET, setParams, null);

        values.query.append(where.query);
        values.args.addAll(where.args);

        return values;
    }

    /** Constructor arguments rebuilding the row just inserted: the parameter values as written, no SQL conversion. */
    public static List<CodeBlock> rowArgs(List<MethodParamInfo> params) {
        List<CodeBlock> args = new ArrayList<>();
        for (MethodParamInfo param : params)
            for (ColumnInfo column : param.columns)
                if (!(param.dataClass && column.idGenerator != null))
                    args.add(CodeBlock.of("$L", argName(param, column)));
        return args;
    }

    // A data-class parameter contributes one accessor per column; a plain parameter is its own value
    private static String argName(MethodParamInfo param, ColumnInfo column) {
        if (!param.dataClass)
            return param.paramName;
        return param.paramName + '.' + column.field.getSimpleName() + (param.isRecord ? "()" : "");
    }

    private static QueryAndArgs clause(Clause clause, List<MethodParamInfo> params, MethodInfo methodInfo) {
        StringBuilder queryBuilder = new StringBuilder();
        List<CodeBlock> args = new ArrayList<>();

        boolean haveCustomSql = methodInfo != null && methodInfo.querySqlParams != null;
        boolean customSqlOverride = haveCustomSql && methodInfo.querySql.override();
        if (!params.isEmpty() || haveCustomSql) {
            queryBuilder.append(' ');
            if (clause.keyword != null && !customSqlOverride)
                queryBuilder.append(clause.keyword);
        }
        int conditionCount = -1;
        if (!customSqlOverride) {
            if (clause == Clause.INSERT)
                queryBuilder.append("(");

            for (MethodParamInfo param : params) {
                if (param.columns.isEmpty()) continue;
                if (++conditionCount != 0) queryBuilder.append(clause.separator);

                // One WHERE parameter over several columns matches any of them
                boolean anyColumn = clause == Clause.WHERE && param.columns.size() > 1;
                if (anyColumn)
                    queryBuilder.append("(");
                int j = -1;
                for (ColumnInfo column : param.columns) {
                    if (param.dataClass && column.idGenerator != null)
                        continue;
                    if (++j != 0) queryBuilder.append(clause == Clause.WHERE ? " OR " : ",");

                    switch (clause) {
                        case INSERT -> queryBuilder.append(quoteColumn(column.columnName));
                        case SET -> queryBuilder.append(quoteColumn(column.columnName))
                                .append(param.whereOperation).append('?')
                                .append(enumBindCast(column, column.isArray));
                        case WHERE -> {
                            if (param.ignoreCase)
                                queryBuilder.append("LOWER(").append(quoteColumn(column.columnName)).append(") ").append(param.whereOperation).append(" LOWER(?)");
                            else
                                queryBuilder.append(quoteColumn(column.columnName))
                                        .append(column.nullable ? " IS NOT DISTINCT FROM " : param.whereOperation).append('?')
                                        .append(enumBindCast(column, column.isArray));
                        }
                    }

                    appendArgs(args, argName(param, column), column.isArray, column.isList, column.isEnum, column.nullable);
                }
                if (anyColumn)
                    queryBuilder.append(")");
            }
            if (clause == Clause.INSERT)
                queryBuilder.append(')');
        }

        if (methodInfo != null && clause == Clause.INSERT) {
            queryBuilder.append("VALUES(");
            boolean first = true;
            for (MethodParamInfo info : methodInfo.params) {
                for (ColumnInfo column : info.columns) {
                    if (!first) queryBuilder.append(',');
                    first = false;
                    queryBuilder.append('?').append(enumBindCast(column, column.isArray));
                }
            }
            queryBuilder.append(')');
        }

        // Custom @QuerySQL, joined to the generated conditions by its own conjunction
        if (methodInfo != null && methodInfo.querySql != null && methodInfo.querySqlParams != null) {
            if (conditionCount > -1 && clause != Clause.INSERT)
                queryBuilder.append(' ').append(methodInfo.querySql.conjunction()).append(' ');

            for (SqlParamInfo sqlParam : methodInfo.querySqlParams) {
                queryBuilder.append(sqlParam.sqlPart());
                if (sqlParam.paramName() == null)
                    continue;
                queryBuilder.append('?');

                MethodParamInfo methodParam = sqlParam.methodParamInfo();
                if (methodParam != null && !methodParam.columns.isEmpty())
                    queryBuilder.append(enumBindCast(methodParam.columns.getFirst(), methodParam.isArray));

                boolean isArray = methodParam != null && methodParam.isArray;
                boolean isList = isArray && !(methodParam.parameter.asType() instanceof ArrayType);
                boolean isEnum = methodParam != null && methodParam.isEnum;

                appendArgs(args, sqlParam.paramName(), isArray, isList, isEnum, true);
            }
        }
        return new QueryAndArgs(queryBuilder, args);
    }

    // An enum array converts to String[] in place: the driver binds no enum, and a per-method temp variable needs its declaration and its use kept in step
    private static void appendArgs(List<CodeBlock> args, String argName, boolean isArray, boolean isList, boolean isEnum, boolean nullable) {
        if (isArray) {
            CodeBlock value;
            if (!isEnum)
                value = CodeBlock.of("$L", argName);
            else if (isList)
                value = CodeBlock.of("$L.stream().map($T::name).toArray($T[]::new)", argName, Enum.class, String.class);
            else
                value = CodeBlock.of("$T.stream($L).map($T::name).toArray($T[]::new)", Arrays.class, argName, Enum.class, String.class);
            args.add(CodeBlock.of("new $T($T.ARRAY, $L)",
                    SqlParameterValue.class,
                    java.sql.Types.class,
                    value
            ));
        } else if (isEnum) {
            if (nullable)
                args.add(CodeBlock.of("$L == null ? null : $L.name()", argName, argName));
            else
                args.add(CodeBlock.of("$L.name()", argName));
        } else {
            args.add(CodeBlock.of("$L", argName));
        }
    }

    public static MethodSpec.Builder getClassDefinition(MethodInfo methodInfo) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodInfo.methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(methodInfo.returnTypeMirror));

        for (MethodParamInfo param : methodInfo.params) {
            TypeName typeName = TypeName.get(param.parameter.asType());

            if (methodInfo.batchInsert) {
                methodBuilder.addParameter(ParameterizedTypeName.get(ClassName.get(List.class), typeName), param.paramName + "_");
            } else {
                methodBuilder.addParameter(typeName, param.paramName);
            }
        }
        return methodBuilder;
    }

    public static void buildJdbcQueryObject(MethodSpec.Builder methodBuilder, String sql, List<CodeBlock> args, Class<?> returnType, boolean checkExistence) {
        String queryStmt;
        Object[] queryArgs;
        if (args.isEmpty()) {
            queryStmt = "jdbc.queryForObject($S, $T.class)";
            queryArgs = new Object[]{sql, returnType};
        } else {
            queryStmt = "jdbc.queryForObject($S, $T.class, $L)";
            queryArgs = new Object[]{sql, returnType, CodeBlock.join(args, ", ")};
        }

        if (checkExistence) {
            methodBuilder.addStatement("return " + queryStmt + " > 0", queryArgs);
        } else {
            methodBuilder.addStatement("return " + queryStmt, queryArgs);
        }
    }

    public static void buildJdbcUpdate(MethodSpec.Builder methodBuilder, String sql, List<CodeBlock> args, TypeMirror returnType) {
        String updateStmt;
        Object[] updateArgs;
        if (args.isEmpty()) {
            updateStmt = "jdbc.update($S)";
            updateArgs = new Object[]{sql};
        } else {
            updateStmt = "jdbc.update($S, $L)";
            updateArgs = new Object[]{sql, CodeBlock.join(args, ", ")};
        }

        if (returnType.getKind() == TypeKind.VOID) {
            methodBuilder.addStatement(updateStmt, updateArgs);
        } else if (TypeName.get(returnType).equals(TypeName.BOOLEAN) || TypeName.get(returnType).equals(TypeName.BOOLEAN.box())) {
            methodBuilder.addStatement("return " + updateStmt + " > 0", updateArgs);
        } else {
            methodBuilder.addStatement("return " + updateStmt, updateArgs);
        }
    }

    /** {@code mapperField}: the generated FastResultSetExtractor field to read rows with; null -> single column via queryForList. */
    public static void buildJdbcQueryReturn(MethodSpec.Builder methodBuilder, MethodInfo methodInfo, String sql, List<CodeBlock> args, String mapperField) {
        boolean useMapper = mapperField != null;
        TypeName returnType = TypeName.get(methodInfo.returnTypeMirror);
        TypeName elementTypeName;
        if (methodInfo.returns.list() && methodInfo.returnTypeMirror instanceof DeclaredType declaredType) {
            List<? extends TypeMirror> argsList = declaredType.getTypeArguments();
            if (!argsList.isEmpty()) {
                elementTypeName = TypeName.get(argsList.get(0)).box();
            } else {
                elementTypeName = ClassName.get(Object.class);
            }
        } else {
            elementTypeName = returnType.box();
        }

        String queryMethod = useMapper ? "jdbc.query" : "jdbc.queryForList";
        CodeBlock queryArgs;
        if (useMapper) {
            if (args.isEmpty()) {
                queryArgs = CodeBlock.of("$S, $N", sql, mapperField);
            } else {
                queryArgs = CodeBlock.of("$S, $N, $L", sql, mapperField, CodeBlock.join(args, ", "));
            }
        } else {
            if (args.isEmpty()) {
                queryArgs = CodeBlock.of("$S, $T.class", sql, elementTypeName);
            } else {
                queryArgs = CodeBlock.of("$S, $T.class, $L", sql, elementTypeName, CodeBlock.join(args, ", "));
            }
        }

        if (methodInfo.returns.list()) {
            methodBuilder.addStatement("return $L($L)", queryMethod, queryArgs);
        } else {
            methodBuilder.addStatement("$T<$T> result = $L($L)", List.class, elementTypeName, queryMethod, queryArgs);

            if (methodInfo.returnTypeMirror instanceof PrimitiveType primitiveType &&
                    primitiveType.getKind() == TypeKind.INT) {
                methodBuilder.addStatement("return result.isEmpty() ? 0 : result.get(0)");
            } else if (methodInfo.returnTypeMirror instanceof PrimitiveType || methodInfo.notFound != null) {
                ClassName exceptionClass = methodInfo.notFound.exception() != null
                        ? ClassName.get(methodInfo.notFound.exception())
                        : ClassName.get(RuntimeException.class);

                methodBuilder.beginControlFlow("if (result.isEmpty())");
                if (methodInfo.notFound.args() == null)
                    methodBuilder.addStatement("throw new $T()", exceptionClass);
                else
                    methodBuilder.addStatement("throw new $T($L)", exceptionClass, methodInfo.notFound.args());
                methodBuilder.endControlFlow();
                methodBuilder.addStatement("return result.get(0)");
            } else {
                methodBuilder.addStatement("return result.isEmpty() ? null : result.get(0)");
            }
        }
    }

    public static CodeBlock addIdGenerator(TableData tableData, List<MethodParamInfo> infos) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();
        int i = -1;
        // Create Ids
        for (ColumnInfo info : tableData.tableFields.values()) {
            ++i;
            if (info.idGenerator == null) continue;
            String generator = tableData.getDependencyFieldName(info.idGenerator.toString());
            codeBlock.addStatement("long id$L = $L.nextId()", i, generator);
            infos.add(i, new MethodParamInfo(null, Collections.singletonList(info), "long", "id" + i, false, null, false));
        }
        return codeBlock.build();
    }
}
