package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.MethodInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.model.TableInfo;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static javax.tools.Diagnostic.Kind.ERROR;

public class SqlGenerator {
    private static final Set<String> reservedWords = readResource("/sqlReservedWords.txt");
    private static final Set<String> unreservedWords = readResource("/sqlUnreservedWords.txt");
    private static final Set<String> reservedColumnNames = readResource("/sqlReservedColumnNames.txt");
    private static final Set<String> reservedTypeFuncNames = readResource("/sqlReservedTypeFuncNames.txt");

    private static Set<String> readResource(String path) {
        try (InputStream stream = Objects.requireNonNull(SqlGenerator.class.getResourceAsStream(path));
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return reader.lines()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + path, e);
        }
    }

    public static boolean generateCreateViewSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        TableData base = tableData.getVirtualBaseTableData();
        String baseTableShortName = "B";

        StringBuilder joinSql = new StringBuilder();

        builder.append("CREATE OR REPLACE VIEW ").append(tableInfo.quotedTableFullName).append(" AS SELECT ");
        boolean first = true;
        Map<TableData, List<ColumnInfo>> joinInfoMap = new LinkedHashMap<>();
        for (ColumnInfo columnInfo : tableData.tableFields.values()) {
            if (!first) builder.append(',');
            first = false;

            String targetTableFullName = base.equals(columnInfo.tableData)
                    ? baseTableShortName : columnInfo.tableInfo.quotedTableFullName;

            if (columnInfo.defaultValue != null)
                builder.append("COALESCE(");

            builder.append(targetTableFullName).append('.').append(columnInfo.quotedColumnName);

            if (columnInfo.defaultValue != null)
                builder.append(", ").append(columnInfo.defaultValue).append(") as ").append(columnInfo.quotedColumnName);

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
                    ? baseTableShortName : referenceTable.tableInfo.quotedTableFullName;
            joinSql.append("\n");
            joinSql.append("    LEFT JOIN ").append(referenceTableFullName).append(" ON ");

            boolean firstJoin = true;
            for (ColumnInfo columnInfo : entry.getValue()) {
                String targetTableFullName = base.equals(columnInfo.tableData)
                        ? baseTableShortName : columnInfo.tableInfo.quotedTableFullName;

                ColumnInfo referenceColumn = columnInfo.getReferencedColumn();

                if (!firstJoin)
                    joinSql.append(" AND ");
                firstJoin = false;
                joinSql.append(targetTableFullName).append('.').append(columnInfo.quotedColumnName).append('=')
                        .append(referenceTableFullName).append('.').append(referenceColumn.quotedColumnName);
            }
        }

        builder.append("\n");
        builder.append("    FROM ").append(base.tableInfo.quotedTableFullName).append(" ").append(baseTableShortName);
        builder.append(joinSql);
        builder.append(";");

        return false;
    }

    public static boolean generateCreateTableSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        builder.append("CREATE TABLE IF NOT EXISTS ").append(tableInfo.quotedTableFullName).append("(");
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (!first) builder.append(',');
            builder.append("\n");
            first = false;

            // Create field
            boolean columnDef = columnInfo.column != null;

            // column name
            builder.append("    ").append(columnInfo.quotedColumnName);

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
                builder.append(tableData.primaryKey.get(i).quotedColumnName);
            }
            builder.append(")");
        }
        for (List<ColumnInfo> uniqueKey : tableData.uniqueKeyList) {
            builder.append(",\n");
            builder.append("    CONSTRAINT").append(tableInfo.getUniqueKey(uniqueKey)).append(" UNIQUE (");
            for (int i = 0; i < uniqueKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(uniqueKey.get(i).quotedColumnName);
            }
            builder.append(")");
        }

        for (TableData.ForeignKeyGroup foreignKeyGroup : tableData.foreignKeyList) {
            String foreignKeyName = tableInfo.name + '_' + String.join("__", foreignKeyGroup.sourceColumns()) + "_FK";

            builder.append(",\n");
            builder.append("    CONSTRAINT ").append(foreignKeyName).append(" FOREIGN KEY (")
                    .append(String.join(",", foreignKeyGroup.sourceColumns()).toLowerCase()).append(") REFERENCES ");
            // If main table have schema, referenced table have to specify as well
            if (tableInfo.schema != null || foreignKeyGroup.referencedTable().schema != null) {
                String referencedTableSchema = foreignKeyGroup.referencedTable().schema;
                if (referencedTableSchema == null)
                    referencedTableSchema = convertPropertyNameToUnderscoreName("PUBLIC").toLowerCase();
                builder.append(referencedTableSchema).append('.');
            }
            builder.append(foreignKeyGroup.referencedTable().name.toLowerCase()).append("(").append(String.join(",", foreignKeyGroup.referencedColumns()).toLowerCase()).append(")");
        }

        builder.append("\n);");

        return false;
    }

    private static boolean generateColumnDefinition(ColumnInfo columnInfo, TableInfo tableInfo, StringBuilder tableCreateSql, Messager console) {
        VariableElement field = columnInfo.field;
        TypeMirror type = field.asType();
        String columnType = toSqlType(type, columnInfo.column);
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
                builder.append(column.quotedColumnName).append(" ").append(methodInfo.orderBy[i].direction().name());
                if (i < methodInfo.orderBy.length - 1)
                    builder.append(", ");
            }
        }
        if (methodInfo.limit != null)
            builder.append(" LIMIT ").append(methodInfo.limit);
        return builder;
    }

    public static String quoteColumnName(String columnName) {
        if (reservedWords.contains(columnName) || reservedColumnNames.contains(columnName) || reservedTypeFuncNames.contains(columnName))
            return '"' + columnName + '"';
        return columnName;
    }

    public static String quoteSchemaTableName(String tableName) {
        if (reservedWords.contains(tableName) || reservedTypeFuncNames.contains(tableName) || unreservedWords.contains(tableName))
            return '"' + tableName + '"';
        return tableName;
    }

    public static String toSqlType(TypeMirror type, Column column) {
        TypeKind kind = type.getKind();


        if (type instanceof DeclaredType declaredType) {
            String typeName = type.toString();
            // String
            if (typeName.startsWith(String.class.getName())) {
                if (column != null && column.length() != -1) {
                    return "VARCHAR(" + column.length() + ")";
                }
                return "VARCHAR";
            }

            // Check if type is java.util.List
            else if (declaredType.asElement().toString().equals(List.class.getName())) {
                TypeMirror componentType = declaredType.getTypeArguments().get(0);
                return toSqlType(componentType, column) + " ARRAY";
            } else if (type.toString().equals(java.sql.Date.class.getName())) {
                return "DATE";
            } else if (type.toString().equals(java.sql.Timestamp.class.getName())) {
                return "TIMESTAMP";
            }
            // Check if type is enum
            else if (declaredType.asElement() instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.ENUM) {
                if (column != null && column.length() != -1) {
                    return "VARCHAR(" + column.length() + ")";
                }
            } else if (typeName.startsWith(Boolean.class.getName())) kind = TypeKind.BOOLEAN;
            else if (typeName.startsWith(Byte.class.getName())) kind = TypeKind.BYTE;
            else if (typeName.startsWith(Short.class.getName())) kind = TypeKind.SHORT;
            else if (typeName.startsWith(Integer.class.getName())) kind = TypeKind.INT;
            else if (typeName.startsWith(Long.class.getName())) kind = TypeKind.LONG;
            else if (typeName.startsWith(Float.class.getName())) kind = TypeKind.FLOAT;
            else if (typeName.startsWith(Double.class.getName())) kind = TypeKind.DOUBLE;
            else if (typeName.startsWith(Character.class.getName())) kind = TypeKind.CHAR;
        }

        if (type instanceof ArrayType arrayType) {
            TypeMirror arrType = arrayType.getComponentType();

            // Auto-detect byte[] to BYTEA
            if (arrType.getKind() == TypeKind.BYTE)
                return "BYTEA";

            return toSqlType(arrType, column) + " ARRAY";
        }

        switch (kind) {
            case BOOLEAN:
                return "BOOLEAN";
            case BYTE:
                return "SMALLINT";
            case SHORT:
                return "SMALLINT";
            case INT:
                return "INTEGER";
            case LONG:
                return "BIGINT";
            case FLOAT:
                return "REAL";
            case DOUBLE:
                if (column != null && column.precision() != 0) {
                    return column.scale() != 0
                            ? "NUMERIC(" + column.precision() + "," + column.scale() + ")"
                            : "NUMERIC(" + column.precision() + ")";
                } else {
                    return "DOUBLE PRECISION";
                }
            case CHAR:
                return "CHAR(1)";
            default:
                return null;
        }
    }
}
