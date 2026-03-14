package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.MethodInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.model.TableInfo;
import com.wavjaby.jdbc.annotation.Column;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static javax.tools.Diagnostic.Kind.ERROR;

public class SqlGenerator {

    public static boolean generateCreateViewSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        TableData base = tableData.getVirtualBaseTableData();
        String baseTableShortName = "B";

        StringBuilder joinSql = new StringBuilder();

        builder.append("CREATE OR REPLACE VIEW ");
        if (tableInfo.schema != null) builder.append(tableInfo.schema).append('.');
        builder.append(tableInfo.name).append(" AS SELECT ");
        boolean first = true;
        for (ColumnInfo columnInfo : tableData.tableFields.values()) {
            if (!first) builder.append(',');
            first = false;

            String targetTableFullName = base.tableInfo.equals(columnInfo.tableInfo)
                    ? baseTableShortName: columnInfo.tableInfo.tableFullname;

            if (columnInfo.defaultValue != null)
                builder.append("COALESCE(");

            builder.append(targetTableFullName).append('.').append(columnInfo.columnName);

            if (columnInfo.defaultValue != null)
                builder.append(", ").append(columnInfo.defaultValue).append(") as ").append(columnInfo.columnName);

            // Build join part
            if (columnInfo.joinColumn != null) {
                ColumnInfo referenceColumn = columnInfo.getReferencedColumnInfo();
                TableData referenceTable = columnInfo.getReferencedTableData();
                String referenceTableFullName = base.tableInfo.equals(referenceTable.tableInfo)
                        ? baseTableShortName : referenceTable.tableInfo.tableFullname;

                joinSql.append("\n");
                joinSql.append("    LEFT JOIN ").append(referenceTableFullName).append(" ON ")
                        .append(targetTableFullName).append('.').append(columnInfo.columnName).append('=')
                        .append(referenceTableFullName).append('.').append(referenceColumn.columnName);
            }
        }
        builder.append("\n");
        builder.append("    FROM ");
        if (base.tableInfo.schema != null) builder.append(base.tableInfo.schema).append(".");
        builder.append(base.tableInfo.name).append(" ").append(baseTableShortName);
        builder.append(joinSql);
        builder.append(";");

        return false;
    }

    public static boolean generateCreateTableSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        builder.append("CREATE TABLE IF NOT EXISTS ");
        if (tableInfo.schema != null) builder.append(tableInfo.schema).append('.');
        builder.append(tableInfo.name).append("(");
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (!first) builder.append(',');
            builder.append("\n");
            first = false;

            // Create field
            boolean columnDef = columnInfo.column != null;

            // column name
            builder.append("    ");
            quoteColumnName(builder, columnInfo.columnName);

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
                builder.append(tableData.primaryKey.get(i).columnName.toLowerCase());
            }
            builder.append(")");
        }
        for (List<ColumnInfo> uniqueKey : tableData.uniqueKeyList) {
            builder.append(",\n");
            builder.append("    CONSTRAINT").append(tableInfo.getUniqueKey(uniqueKey)).append(" UNIQUE (");
            for (int i = 0; i < uniqueKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(uniqueKey.get(i).columnName.toLowerCase());
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
                builder.append(column.columnName).append(" ").append(methodInfo.orderBy[i].direction().name());
                if (i < methodInfo.orderBy.length - 1)
                    builder.append(", ");
            }
        }
        if (methodInfo.limit != null)
            builder.append(" LIMIT ").append(methodInfo.limit);
        return builder;
    }

    public static StringBuilder quoteColumnName(StringBuilder sb, String columnName) {
        return sb.append('"').append(columnName.toLowerCase()).append('"');
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
