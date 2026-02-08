package com.wavjaby.jdbc.processor.util;


import com.squareup.javapoet.*;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.MethodInfo;
import com.wavjaby.jdbc.processor.model.MethodParamInfo;
import com.wavjaby.jdbc.processor.model.QueryParamInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import org.springframework.jdbc.core.SqlParameterValue;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;

import static com.wavjaby.jdbc.processor.util.SqlGenerator.quoteColumnName;

public class JdbcCodeGenerator {

    public record QueryAndArgs(StringBuilder query, List<CodeBlock> args) {
    }

    public static QueryAndArgs getQueryAndArgs(List<MethodParamInfo> params, MethodInfo methodInfo, boolean insert, boolean update, String prefix, String conjunction, boolean tableConstructor, TableData tableData) {
        StringBuilder queryBuilder = new StringBuilder();
        List<CodeBlock> args = new ArrayList<>();

        if (!tableConstructor && !params.isEmpty() || methodInfo != null && methodInfo.querySql != null) {
            if (prefix != null) queryBuilder.append(prefix);
        }

        if (insert)
            queryBuilder.append("(");

        int tempVarCount = 0;

        int conditionCount = -1;
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
                    if (++j != 0) queryBuilder.append(" or ");
                }

                // Query where with ignore case
                if (!update && param.ignoreCase) {
                    queryBuilder.append("LOWER(");
                    quoteColumnName(queryBuilder, column.columnName);
                    queryBuilder.append(") ").append(param.whereOperation).append(" LOWER(?)");
                } else if (insert)
                    quoteColumnName(queryBuilder, column.columnName);
                else {
                    if (!update && column.nullable) {
                        quoteColumnName(queryBuilder, column.columnName);
                        queryBuilder.append(" IS NOT DISTINCT FROM ?");
                    } else {
                        quoteColumnName(queryBuilder, column.columnName).append(param.whereOperation).append('?');
                    }
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

        // Appends query SQL and parameters if present
        if (methodInfo != null && methodInfo.querySql != null) {
            if (conditionCount > -1)
                queryBuilder.append(' ').append(methodInfo.querySql.conjunction()).append(' ');

            for (QueryParamInfo sqlParam : methodInfo.querySqlParams) {
                queryBuilder.append(sqlParam.sqlPart);
                if (sqlParam.paramName == null)
                    continue;
                queryBuilder.append('?');

                MethodParamInfo methodParam = sqlParam.getMethodParamInfo();

                if (methodParam != null && methodParam.parameter.asType() instanceof ArrayType) {
                    args.add(CodeBlock.of("new $T($T.ARRAY, $L)",
                            SqlParameterValue.class,
                            java.sql.Types.class,
                            sqlParam.paramName
                    ));
                } else {
                    args.add(CodeBlock.of("$L", sqlParam.paramName));
                }

            }
        }
        return new QueryAndArgs(queryBuilder, args);
    }

    public static QueryAndArgs updateQueryAndArgs(List<MethodParamInfo> whereColumns, List<MethodParamInfo> updateColumns, MethodInfo methodInfo, TableData tableData) {
        QueryAndArgs where = getQueryAndArgs(whereColumns, methodInfo, false, false, " where ", " and ", false, tableData);
        QueryAndArgs values = getQueryAndArgs(updateColumns, null, false, true, " set ", ",", false, tableData);

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
        if (methodInfo.returnList && methodInfo.returnTypeMirror instanceof DeclaredType declaredType) {
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

        if (methodInfo.returnList) {
            methodBuilder.addStatement("return $L($L)", queryMethod, queryArgs);
        } else {
            methodBuilder.addStatement("$T<$T> result = $L($L)", List.class, elementTypeName, queryMethod, queryArgs);

            if (returnType.isPrimitive()) {
                methodBuilder.addStatement("return result.isEmpty() ? 0 : result.get(0)");
            } else {
                methodBuilder.addStatement("return result.isEmpty() ? null : result.get(0)");
            }
        }
    }

    public static void checkAndConvertEnumToStringArray(MethodSpec.Builder methodBuilder, List<MethodParamInfo> infos) {
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
                    methodBuilder.addStatement("$T[] var$L = new $T[$L.length]", String.class, tempVarCount, String.class, argName);
                    methodBuilder.beginControlFlow("for (int i = 0; i < $L.length; i++)", argName);
                    methodBuilder.addStatement("var$L[i] = $L[i].name()", tempVarCount, argName);
                    methodBuilder.endControlFlow();
                    tempVarCount++;
                }
            }
        }
    }
}
