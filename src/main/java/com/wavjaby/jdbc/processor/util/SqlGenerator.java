package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.MethodInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.model.TableInfo;

import javax.annotation.processing.Messager;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.PostgresDialect.*;
import static javax.tools.Diagnostic.Kind.ERROR;

public class SqlGenerator {
    public static boolean generateCreateViewSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        TableData base = tableData.getVirtualBaseTableData();
        String baseTableShortName = "B";

        StringBuilder joinSql = new StringBuilder();

        builder.append("CREATE OR REPLACE VIEW ").append(quoteTable(tableInfo)).append(" AS SELECT ");
        boolean first = true;
        Map<TableData, List<ColumnInfo>> joinInfoMap = new LinkedHashMap<>();
        for (ColumnInfo columnInfo : tableData.tableFields.values()) {
            if (!first) builder.append(',');
            first = false;

            String targetTableFullName = base.equals(columnInfo.tableData)
                    ? baseTableShortName : quoteTable(columnInfo.tableInfo);

            if (columnInfo.defaultValue != null)
                builder.append("COALESCE(");

            String sourceColumnName = columnInfo.getSourceColumnName();
            builder.append(targetTableFullName).append('.').append(quoteColumn(sourceColumnName));

            if (columnInfo.defaultValue != null)
                builder.append(", ").append(columnInfo.defaultValue).append(')');
            if (columnInfo.defaultValue != null || !sourceColumnName.equals(columnInfo.columnName))
                builder.append(" as ").append(quoteColumn(columnInfo.columnName));

            // Add reference column join info
            if (columnInfo.joinColumn != null) {
                ColumnInfo referenceColumn = columnInfo.getReferencedColumn();
                List<ColumnInfo> columnJoinInfo = joinInfoMap.computeIfAbsent(referenceColumn.tableData, k -> new ArrayList<>());
                columnJoinInfo.add(columnInfo);
            }
        }
        // Build join part
        for (Map.Entry<TableData, List<ColumnInfo>> entry : joinInfoMap.entrySet()) {
            TableData referenceTable = entry.getKey();
            String referenceTableFullName = base.equals(referenceTable)
                    ? baseTableShortName : quoteTable(referenceTable.tableInfo);
            joinSql.append("\n");
            joinSql.append("    LEFT JOIN ").append(referenceTableFullName).append(" ON ");

            boolean firstJoin = true;
            for (ColumnInfo columnInfo : entry.getValue()) {
                String targetTableFullName = base.equals(columnInfo.tableData)
                        ? baseTableShortName : quoteTable(columnInfo.tableInfo);

                ColumnInfo referenceColumn = columnInfo.getReferencedColumn();

                if (!firstJoin)
                    joinSql.append(" AND ");
                firstJoin = false;
                joinSql.append(targetTableFullName).append('.').append(quoteColumn(columnInfo.getSourceColumnName())).append('=')
                        .append(referenceTableFullName).append('.').append(quoteColumn(referenceColumn.columnName));
            }
        }

        builder.append("\n");
        builder.append("    FROM ").append(quoteTable(base.tableInfo)).append(" ").append(baseTableShortName);
        builder.append(joinSql);
        builder.append(";");

        return false;
    }

    public static boolean generateCreateTableSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        builder.append("CREATE TABLE IF NOT EXISTS ").append(quoteTable(tableInfo)).append("(");
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (!first) builder.append(',');
            builder.append("\n");
            first = false;

            // Create field
            boolean columnDef = columnInfo.column != null;

            // column name
            builder.append("    ").append(quoteColumn(columnInfo.columnName));

            // If column define by user
            if (columnDef && !columnInfo.column.columnDefinition().isEmpty()) {
                builder.append(' ').append(columnInfo.column.columnDefinition());
            }
            // Auto create column define
            else if (generateColumnDefinition(columnInfo, tableInfo, builder, console)) {
                console.printMessage(ERROR, "Could not generate column definition for column: " + columnInfo.columnName, columnInfo.field);
                return true;
            }

            // Apply default value
            if (columnInfo.defaultValue != null) {
                builder.append(" DEFAULT ");
                if (columnInfo.isString)
                    builder.append('\'').append(columnInfo.defaultValue).append('\'');
                else
                    builder.append(columnInfo.defaultValue);
            }
            if (!columnInfo.nullable) builder.append(" NOT NULL");

        }
        if (!tableData.primaryKey.isEmpty()) {
            builder.append(",\n");
            builder.append("    CONSTRAINT ").append(tableInfo.name).append("_PK");
            builder.append(" PRIMARY KEY(");
            for (int i = 0; i < tableData.primaryKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(quoteColumn(tableData.primaryKey.get(i).columnName));
            }
            builder.append(")");
        }
        for (List<ColumnInfo> uniqueKey : tableData.uniqueKeyList) {
            builder.append(",\n");
            builder.append("    CONSTRAINT").append(tableInfo.getUniqueKey(uniqueKey)).append(" UNIQUE (");
            for (int i = 0; i < uniqueKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(quoteColumn(uniqueKey.get(i).columnName));
            }
            builder.append(")");
        }

        for (TableData.ForeignKeyGroup foreignKeyGroup : tableData.foreignKeyList) {
            String foreignKeyName = tableInfo.name + '_' + String.join("__", foreignKeyGroup.sourceColumns) + "_FK";

            builder.append(",\n");
            builder.append("    CONSTRAINT ").append(foreignKeyName).append(" FOREIGN KEY (")
                    .append(String.join(",", foreignKeyGroup.sourceColumns).toLowerCase()).append(") REFERENCES ")
                    .append(quoteTable(foreignKeyGroup.referencedTable)).append("(").append(String.join(",", foreignKeyGroup.referencedColumns).toLowerCase()).append(")");
            appendReferentialAction(builder, "ON UPDATE", foreignKeyGroup.onUpdate);
            appendReferentialAction(builder, "ON DELETE", foreignKeyGroup.onDelete);
        }

        builder.append("\n);");

        return false;
    }

    // NO_ACTION is the SQL default, so it is left implicit and existing DDL stays byte-identical
    private static void appendReferentialAction(StringBuilder builder, String clause, JoinColumn.Action action) {
        if (action == null || action == JoinColumn.Action.NO_ACTION)
            return;
        builder.append(' ').append(clause).append(' ').append(action == JoinColumn.Action.CASCADE ? "CASCADE" : "SET NULL");
    }

    private static boolean generateColumnDefinition(ColumnInfo columnInfo, TableInfo tableInfo, StringBuilder tableCreateSql, Messager console) {
        VariableElement field = columnInfo.field;
        TypeMirror type = field.asType();
        String columnType = columnType(type, columnInfo.column);
        if (columnType == null) {
            console.printMessage(ERROR, "Unknown SQL type for: " + field.asType().toString(), field);
            return true;
        }

        // column type
        tableCreateSql.append(' ').append(columnType);

        return false;
    }

    public static StringBuilder sqlResultModifier(MethodInfo methodInfo) {
        StringBuilder builder = new StringBuilder();
        if (methodInfo.orderBy != null) {
            builder.append(" ORDER BY ");
            for (int i = 0; i < methodInfo.orderBy.length; i++) {
                ColumnInfo column = methodInfo.orderBy[i].column();
                builder.append(quoteColumn(column.columnName)).append(" ").append(methodInfo.orderBy[i].direction().name());
                if (i < methodInfo.orderBy.length - 1)
                    builder.append(", ");
            }
        }
        if (methodInfo.limit != null)
            builder.append(" LIMIT ").append(methodInfo.limit);
        return builder;
    }
}
