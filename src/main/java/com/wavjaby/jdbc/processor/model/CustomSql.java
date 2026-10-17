package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.annotation.QuerySQL;
import com.wavjaby.jdbc.annotation.Select;
import com.wavjaby.jdbc.processor.EmptyProcessingException;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.WARNING;

/** The SQL a method writes itself -- @Select(columnSql) and @QuerySQL -- split at its :name parameters. Null list = not written. */
public record CustomSql(List<SqlParam> column, List<SqlParam> query) {
    private static final Pattern paramPattern = Pattern.compile(":([a-zA-Z_]\\w*)(\\.\\w+)*");

        public record SqlParam(String sql, String paramName) {
        }

        public boolean isParamExist(String paramName) {
            if (query != null && query.stream().anyMatch(param -> paramName.equals(param.paramName)))
                return true;
            if (column != null && column.stream().anyMatch(param -> paramName.equals(param.paramName)))
                return true;
            return false;
        }

    static CustomSql parse(Select select, QuerySQL querySQL, ExecutableElement method, Messager console) throws EmptyProcessingException {
        List<SqlParam> column = null, query = null;

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


        return new CustomSql(column, query);
    }

    static SqlParamInfo[] initSqlParams(String keyName, List<SqlParam> sqlParams, List<MethodParamInfo> methodParams, ExecutableElement method, Messager console) throws EmptyProcessingException {
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

    private static List<SqlParam> parseSqlParam(String sql) {
        String returnColumnSql = sql.replaceAll(" *\r?\n *", " ");
        Matcher matcher = paramPattern.matcher(returnColumnSql);
        int index = 0;
        List<SqlParam> result = new ArrayList<>();
        while (matcher.find()) {
            String pramName = matcher.group().substring(1);

            result.add(new SqlParam(returnColumnSql.substring(index, matcher.start()), pramName));
            index = matcher.end();
        }
        result.add(new SqlParam(returnColumnSql.substring(index), null));
        return result;
    }
}
