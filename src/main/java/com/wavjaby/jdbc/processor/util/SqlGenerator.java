package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.MethodInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.model.TableInfo;
import com.wavjaby.persistence.Column;

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

        // Find table reference
        List<TableData> referencedTable = new ArrayList<>();
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            ColumnInfo columnInfo = entry.getValue();
            if (columnInfo.joinColumn != null) {
                referencedTable.add(columnInfo.getReferencedTableData());
            }
        }

        builder.append("create or replace view ");
        if (tableInfo.schema != null) builder.append(tableInfo.schema).append('.');
        builder.append(tableInfo.name).append(" as select ");
        boolean first = true;
        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            String fieldName = entry.getKey();
            ColumnInfo columnInfo = entry.getValue();

            ColumnInfo targetColumn = base.tableFields.get(fieldName);
            String targetTableName = base.tableInfo.name;
            String targetTableSchema = base.tableInfo.schema;
            boolean baseTableColumn = targetColumn != null;
            // Find field from other table
            if (targetColumn == null)
                for (TableData table : referencedTable) {
                    targetColumn = table.tableFields.get(fieldName);
                    if (targetColumn != null) {
                        targetTableName = table.tableInfo.name;
                        targetTableSchema = table.tableInfo.schema;
                        break;
                    }
                }
            if (targetColumn == null) {
                console.printMessage(ERROR, "Field '" + fieldName + "' not found in referenced table", columnInfo.field);
                return true;
            }


            if (!first) builder.append(',');
            first = false;

            if (baseTableColumn)
                builder.append(baseTableShortName).append('.').append(columnInfo.columnName);
            else {
                if (targetTableSchema != null)
                    builder.append(targetTableSchema).append(".");
                builder.append(targetTableName).append('.').append(targetColumn.columnName);
            }
        }
        builder.append("\n");
        builder.append("    from ");
        if (base.tableInfo.schema != null) builder.append(base.tableInfo.schema).append(".");
        builder.append(base.tableInfo.name).append(" ").append(baseTableShortName);

        for (Map.Entry<String, ColumnInfo> entry : tableData.tableFields.entrySet()) {
            String fieldName = entry.getKey();
            ColumnInfo columnInfo = entry.getValue();


            if (columnInfo.joinColumn == null)
                continue;

            TableInfo targetTableInfo = columnInfo.getReferencedTableData().tableInfo;
            String targetTableFullName = targetTableInfo.name;
            if (targetTableInfo.schema != null)
                targetTableFullName = targetTableInfo.schema + "." + targetTableFullName;

            String targetColumnName = columnInfo.getReferencedColumnInfo().columnName;
            builder.append("\n");
            builder.append("    join ").append(targetTableFullName).append(" on ")
                    .append(baseTableShortName).append('.').append(columnInfo.columnName).append('=')
                    .append(targetTableFullName).append('.').append(targetColumnName);
        }
        builder.append(";");

        return false;
    }

    public static boolean generateCreateTableSql(TableData tableData, StringBuilder builder, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        builder.append("create table if not exists ");
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
                builder.append(" default ");
                if (columnInfo.isString)
                    builder.append('\'').append(columnInfo.defaultValue).append('\'');
                else
                    builder.append(columnInfo.defaultValue);
            }
            if (!columnInfo.nullable) builder.append(" not null");

        }
        if (!tableData.primaryKey.isEmpty()) {
            builder.append(",\n");
            builder.append("    constraint ").append(tableInfo.name).append("_PK");
            builder.append(" primary key(");
            for (int i = 0; i < tableData.primaryKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(tableData.primaryKey.get(i).columnName);
            }
            builder.append(")");
        }
        for (List<ColumnInfo> uniqueKey : tableData.uniqueKeyList) {
            builder.append(",\n");
            builder.append("    constraint").append(tableInfo.getUniqueKey(uniqueKey)).append(" unique (");
            for (int i = 0; i < uniqueKey.size(); i++) {
                if (i != 0) builder.append(',');
                builder.append(uniqueKey.get(i).columnName);
            }
            builder.append(")");
        }

        for (TableData.ForeignKeyGroup foreignKeyGroup : tableData.foreignKeyList) {
            String foreignKeyName = tableInfo.name + '_' + String.join("__", foreignKeyGroup.sourceColumns()) + "_FK";

            builder.append(",\n");
            builder.append("    constraint ").append(foreignKeyName).append(" foreign key (")
                    .append(String.join(",", foreignKeyGroup.sourceColumns())).append(") references ");
            // If main table have schema, referenced table have to specify as well
            if (tableInfo.schema != null || foreignKeyGroup.referencedTable().schema != null) {
                String referencedTableSchema = foreignKeyGroup.referencedTable().schema;
                if (referencedTableSchema == null)
                    referencedTableSchema = convertPropertyNameToUnderscoreName("PUBLIC");
                builder.append(referencedTableSchema).append('.');
            }
            builder.append(foreignKeyGroup.referencedTable().name).append("(").append(String.join(",", foreignKeyGroup.referencedColumns())).append(")");
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
        if (methodInfo.orderByColumns != null) {
            builder.append(" ORDER BY ");
            for (int i = 0; i < methodInfo.orderByColumns.length; i++) {
                ColumnInfo column = methodInfo.orderByColumns[i];
                builder.append(column.columnName).append(" ").append(methodInfo.orderBy[i].direction());
                if (i < methodInfo.orderByColumns.length - 1)
                    builder.append(", ");
            }
        }
        if (methodInfo.limit != null)
            builder.append(" LIMIT ").append(methodInfo.limit);
        return builder;
    }

    public static StringBuilder quoteColumnName(StringBuilder sb, String columnName) {
        return sb.append('"').append(columnName).append('"');
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
