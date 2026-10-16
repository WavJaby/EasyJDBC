package com.wavjaby.jdbc.processor.util;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.TableInfo;
import com.wavjaby.jdbc.util.StringConverter;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL vocabulary: identifier quoting, Java type -> column type, native enum types.
 * The model holds bare names and Java types; every emitter spells them through here.
 */
public final class PostgresDialect {
    private static final Set<String> reservedWords = readResource("/sqlReservedWords.txt");
    private static final Set<String> unreservedWords = readResource("/sqlUnreservedWords.txt");
    private static final Set<String> reservedColumnNames = readResource("/sqlReservedColumnNames.txt");
    private static final Set<String> reservedTypeFuncNames = readResource("/sqlReservedTypeFuncNames.txt");

    private PostgresDialect() {
    }

    private static Set<String> readResource(String path) {
        try (InputStream stream = Objects.requireNonNull(PostgresDialect.class.getResourceAsStream(path));
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return reader.lines()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + path, e);
        }
    }

    public static String quoteColumn(String columnName) {
        if (reservedWords.contains(columnName) || reservedColumnNames.contains(columnName) || reservedTypeFuncNames.contains(columnName))
            return '"' + columnName + '"';
        return columnName;
    }

    // Schema-qualified when the table declares a schema
    public static String quoteTable(TableInfo tableInfo) {
        return tableInfo.schema == null
                ? quoteSchemaTableName(tableInfo.name)
                : quoteSchemaTableName(tableInfo.schema) + '.' + quoteSchemaTableName(tableInfo.name);
    }

    private static String quoteSchemaTableName(String tableName) {
        if (reservedWords.contains(tableName) || reservedTypeFuncNames.contains(tableName) || unreservedWords.contains(tableName))
            return '"' + tableName + '"';
        return tableName;
    }

    public static String enumTypeName(TypeMirror enumType) {
        return StringConverter.convertPropertyNameToUnderscoreName(enumType.toString());
    }

    // Idempotent: CREATE TYPE has no IF NOT EXISTS, so the check goes through pg_type
    public static String createEnumTypeSql(String typeName, List<String> values) {
        String valuesStr = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
        return "DO\n" +
                "$$\n" +
                "BEGIN\n" +
                "    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = '" + typeName + "') THEN\n" +
                "        CREATE TYPE " + typeName + " AS ENUM (\n" +
                "        " + valuesStr + "\n" +
                "        );\n" +
                "    END IF;\n" +
                "END\n" +
                "$$;";
    }

    // Enums bind as their name(), a varchar, which PostgreSQL will not coerce into a native enum column without the cast
    public static String enumBindCast(ColumnInfo column, boolean array) {
        if (!column.nativeEnum)
            return "";
        return "::" + enumTypeName(column.type) + (array ? "[]" : "");
    }

    // Not reached for @Column(columnDefinition): that text is the column type verbatim
    public static String columnType(TypeMirror type, ColumnInfo columnInfo) {
        Column column = columnInfo.column;
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
                return columnType(componentType, columnInfo) + " ARRAY";
            } else if (type.toString().equals(java.sql.Date.class.getName()) ||
                    type.toString().equals(java.time.LocalDate.class.getName())) {
                return "DATE";
            } else if (type.toString().equals(java.sql.Timestamp.class.getName())) {
                return "TIMESTAMP";
            }
            // Prefer BigDecimal over double for any NUMERIC column queried by equality or range: it is the only Java type that also binds as numeric
            else if (typeName.startsWith(java.math.BigDecimal.class.getName())) {
                return numericType(column);
            }
            // Check if type is enum
            else if (declaredType.asElement() instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.ENUM) {
                // columnDefinition never gets here, so a non-native enum is the @Column(length) one
                return columnInfo.nativeEnum ? enumTypeName(type) : "VARCHAR(" + column.length() + ")";
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

            return columnType(arrType, columnInfo) + " ARRAY";
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
                // precision/scale on a double still yields NUMERIC, kept for existing schemas; the parameter binds as DOUBLE PRECISION
                return column != null && column.precision() != 0 ? numericType(column) : "DOUBLE PRECISION";
            case CHAR:
                return "CHAR(1)";
            default:
                return null;
        }
    }

    private static String numericType(Column column) {
        if (column == null || column.precision() == 0)
            return "NUMERIC";
        return column.scale() != 0
                ? "NUMERIC(" + column.precision() + "," + column.scale() + ")"
                : "NUMERIC(" + column.precision() + ")";
    }
}
