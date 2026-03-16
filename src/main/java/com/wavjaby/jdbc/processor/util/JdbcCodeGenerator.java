package com.wavjaby.jdbc.processor.util;


import com.squareup.javapoet.*;
import com.wavjaby.jdbc.processor.model.*;
import org.springframework.jdbc.core.SqlParameterValue;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JdbcCodeGenerator {

    public record QueryAndArgs(StringBuilder query, List<CodeBlock> args) {
    }

    public static QueryAndArgs getQueryAndArgs(List<MethodParamInfo> params, MethodInfo methodInfo, boolean insert, boolean update, String prefix, String conjunction, boolean tableConstructor, TableData tableData) {
        StringBuilder queryBuilder = new StringBuilder();
        List<CodeBlock> args = new ArrayList<>();

        boolean haveCustomSql = methodInfo != null && methodInfo.querySqlParams != null;
        boolean customSqlOverride = haveCustomSql && methodInfo.querySql.override();
        if (!tableConstructor && (!params.isEmpty() || haveCustomSql)) {
            queryBuilder.append(' ');
            if (prefix != null && !customSqlOverride)
                queryBuilder.append(prefix);
        }
        int conditionCount = -1;
        if (!customSqlOverride) {
            if (insert)
                queryBuilder.append("(");

            int tempVarCount = 0;

            for (MethodParamInfo param : params) {
                if (param.columns.isEmpty()) continue;
                if (++conditionCount != 0) queryBuilder.append(conjunction);

                // Query with multiple columns
                if (!insert && !update && param.columns.size() > 1)
                    queryBuilder.append("(");
                int j = -1;
                for (ColumnInfo column : param.columns) {
                    if (param.dataClass && column.idGenerator != null)
                        continue;

                    if (insert || update) {
                        if (++j != 0) queryBuilder.append(',');
                    } else {
                        if (++j != 0) queryBuilder.append(" OR ");
                    }

                    // Query where with ignore case
                    if (!update && param.ignoreCase) {
                        queryBuilder.append("LOWER(").append(column.quotedColumnName).append(") ").append(param.whereOperation).append(" LOWER(?)");
                    } else if (insert) {
                        queryBuilder.append(column.quotedColumnName);
                    } else {
                        queryBuilder.append(column.quotedColumnName)
                                .append(!update && column.nullable ? " IS NOT DISTINCT FROM " : param.whereOperation).append('?');
                    }

                    String argName = param.paramName;
                    // Get field if using data class
                    if (param.dataClass) {
                        argName += '.' + column.field.getSimpleName().toString();
                        if (param.isRecord) argName += "()";
                    }

                    if (tableConstructor) {
                        args.add(CodeBlock.of("$L", argName));
                    } else {
                        if (column.isArray) {
                            // If enum array exist
                            if (column.isEnum) {
                                argName = "var" + tempVarCount;
                                tempVarCount++;
                            }

                            args.add(CodeBlock.of("new $T($T.ARRAY, $L)",
                                    SqlParameterValue.class,
                                    java.sql.Types.class,
                                    argName
                            ));
                        } else if (column.isEnum) {
                            if (column.nullable)
                                args.add(CodeBlock.of("$L == null ? null : $L.name()", argName, argName));
                            else
                                args.add(CodeBlock.of("$L.name()", argName));
                        } else {
                            args.add(CodeBlock.of("$L", argName));
                        }
                    }
                }
                if (!insert && !update && param.columns.size() > 1)
                    queryBuilder.append(")");
            }
            if (insert)
                queryBuilder.append(')');
        }

        // Append insert variables
        if (methodInfo != null && insert) {
            queryBuilder.append("VALUES(");
            boolean first = true;
            for (MethodParamInfo info : methodInfo.params) {
                for (int i = 0; i < info.columns.size(); i++) {
                    if (!first) queryBuilder.append(',');
                    first = false;
                    queryBuilder.append('?');
                }
            }
            queryBuilder.append(')');
        }

        // Appends query SQL and parameters if present
        if (methodInfo != null && methodInfo.querySql != null && methodInfo.querySqlParams != null) {
            if (conditionCount > -1 && !insert)
                queryBuilder.append(' ').append(methodInfo.querySql.conjunction()).append(' ');

            for (SqlParamInfo sqlParam : methodInfo.querySqlParams) {
                queryBuilder.append(sqlParam.sqlPart());
                if (sqlParam.paramName() == null)
                    continue;
                queryBuilder.append('?');

                MethodParamInfo methodParam = sqlParam.methodParamInfo();

                if (methodParam != null && methodParam.parameter.asType() instanceof ArrayType) {
                    args.add(CodeBlock.of("new $T($T.ARRAY, $L)",
                            SqlParameterValue.class,
                            java.sql.Types.class,
                            sqlParam.paramName()
                    ));
                } else {
                    args.add(CodeBlock.of("$L", sqlParam.paramName()));
                }

            }
        }
        return new QueryAndArgs(queryBuilder, args);
    }

    public static QueryAndArgs updateQueryAndArgs(List<MethodParamInfo> whereColumns, List<MethodParamInfo> updateColumns, MethodInfo methodInfo, TableData tableData) {
        QueryAndArgs where = getQueryAndArgs(whereColumns, methodInfo, false, false, "WHERE ", " AND ", false, tableData);
        QueryAndArgs values = getQueryAndArgs(updateColumns, null, false, true, "SET ", ",", false, tableData);

        values.query.append(where.query);
        values.args.addAll(where.args);

        return values;
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

    public static void buildJdbcQueryReturn(MethodSpec.Builder methodBuilder, MethodInfo methodInfo, String sql, List<CodeBlock> args, boolean useMapper) {
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
                queryArgs = CodeBlock.of("$S, tableMapper", sql);
            } else {
                queryArgs = CodeBlock.of("$S, tableMapper, $L", sql, CodeBlock.join(args, ", "));
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

    public static CodeBlock checkAndConvertEnumToStringArray(List<MethodParamInfo> infos) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();
        int tempVarCount = 0;
        for (MethodParamInfo param : infos) {
            for (ColumnInfo column : param.columns) {
                if (param.dataClass && column.idGenerator != null)
                    continue;

                String argName = param.paramName;
                if (param.dataClass) {
                    argName += '.' + column.field.getSimpleName().toString();
                    if (param.isRecord) argName += "()";
                }

                if (column.isArray && column.isEnum) {
                    codeBlock.addStatement("$T[] var$L = new $T[$L.length]", String.class, tempVarCount, String.class, argName);
                    codeBlock.beginControlFlow("for (int i = 0; i < $L.length; i++)", argName);
                    codeBlock.addStatement("var$L[i] = $L[i].name()", tempVarCount, argName);
                    codeBlock.endControlFlow();
                    tempVarCount++;
                }
            }
        }
        return codeBlock.build();
    }
}
