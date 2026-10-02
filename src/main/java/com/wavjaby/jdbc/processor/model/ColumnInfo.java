package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.annotation.*;
import com.wavjaby.jdbc.processor.util.SqlGenerator;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.*;
import java.util.List;
import java.util.Map;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationMirror;
import static com.wavjaby.jdbc.processor.util.AnnotationHelper.getAnnotationValueClass;
import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static javax.tools.Diagnostic.Kind.ERROR;

public class ColumnInfo {
    public final Column column;
    public final TableInfo tableInfo;
    public final TableData tableData;

    public final VariableElement field;
    public final TypeMirror type;
    public final boolean isString;
    public final boolean isArray;
    public final boolean isList;
    public final boolean isEnum;
    public final String customEnumType;

    public final String columnName; // lower snake case
    public final String quotedColumnName;
    public final boolean nullable;
    public final boolean isPrimaryKey;
    public final boolean isUniqueKey;
    public final boolean isGroupedUniqueKey;
    public final boolean isForeignKey;
    public final Object defaultValue;

    public final DeclaredType idGenerator;

    public final JoinColumn joinColumn;
    public final String referencedTableClassPath;
    // Set by processTableJoin in the link pass; cross-table links are by instance, so this cannot be final until resolve owns them
    private ColumnInfo referencedColumn;

    // @FieldName source column. Defaults to quotedColumnName so non-virtual tables read it without branching
    private final String quotedSourceColumnName;

    public ColumnInfo(VariableElement field, TableData tableData) {
        this(field, tableData, null, null);
    }

    /**
     * Virtual-table column: declared by {@code field}, stored in {@code tableData}'s table under {@code quotedSourceColumnName}.
     * Null {@code quotedSourceColumnName} -> the column's own name.
     */
    ColumnInfo(VariableElement field, TableData tableData, String quotedSourceColumnName, ColumnInfo referencedColumn) {
        this.referencedColumn = referencedColumn;
        this.column = field.getAnnotation(Column.class);
        this.tableInfo = tableData.tableInfo;
        this.tableData = tableData;
        this.field = field;

        // Get GeneratedValue
        AnnotationMirror GeneratedValue = getAnnotationMirror(field, GenericGenerator.class);
        if (GeneratedValue != null) {
            DeclaredType strategy = getAnnotationValueClass(GeneratedValue, "strategy");
            assert strategy != null;
            this.idGenerator = strategy;
        } else
            this.idGenerator = null;

        // Get JoinColumn
        this.joinColumn = field.getAnnotation(JoinColumn.class);
        if (this.joinColumn != null) {
            // Check if annotation assign referenced class
            AnnotationMirror joinTableColumnMirror = getAnnotationMirror(field, JoinColumn.class);
            DeclaredType referencedClass = getAnnotationValueClass(joinTableColumnMirror, "referencedClass");
            if (referencedClass != null)
                this.referencedTableClassPath = referencedClass.toString();
            else
                this.referencedTableClassPath = null;
        } else
            this.referencedTableClassPath = null;

        boolean nullable = field.getAnnotation(NotNull.class) == null;

        boolean isString = false, isArray = false, isList = false, isEnum = false;
        TypeMirror type = field.asType();
        // Extracts array/list element type; flags array status
        if (type instanceof ArrayType arrayType) {
            type = arrayType.getComponentType();
            // Auto-detect byte[] to BYTEA
            if (type.getKind() != TypeKind.BYTE)
                isArray = true;
        } else if (type instanceof DeclaredType declaredType && declaredType.asElement().toString().equals(List.class.getName())) {
            type = declaredType.getTypeArguments().get(0);
            isArray = true;
            isList = true;
        } else if (type instanceof PrimitiveType)
            nullable = false;

        if (type instanceof DeclaredType declaredType) {
            isString = type.toString().equals(String.class.getName());
            isEnum = declaredType.asElement().getKind() == ElementKind.ENUM;
        }
        this.type = type;
        this.isString = isString;
        this.isArray = isArray;
        this.isList = isList;
        this.isEnum = isEnum;
        this.customEnumType = isEnum && column == null
                ? convertPropertyNameToUnderscoreName(this.type.toString()) : null;

        // Get column info
        this.isPrimaryKey = field.getAnnotation(Id.class) != null;
        ColumnDefault columnDefault = field.getAnnotation(ColumnDefault.class);
        this.defaultValue = columnDefault == null || columnDefault.value().isBlank() ? null : columnDefault.value();
        this.columnName = getColumnName(field);
        this.quotedColumnName = SqlGenerator.quoteColumnName(this.columnName);
        this.quotedSourceColumnName = quotedSourceColumnName != null ? quotedSourceColumnName : this.quotedColumnName;

        boolean isUniqueKey = false;
        boolean isGroupedUniqueKey = false;
        if (column != null) {
            if (!column.nullable())
                nullable = false;
            if (column.unique())
                isUniqueKey = true;
        }
        if (this.joinColumn != null) {
            if (!joinColumn.nullable())
                nullable = false;
            if (joinColumn.unique())
                isUniqueKey = true;
        }
        // Check if this column is in grouped unique key
        for (UniqueConstraint uniqueConstraint : tableInfo.tableAnn.uniqueConstraints()) {
            for (String name : uniqueConstraint.columnNames()) {
                if (name.equals(columnName)) {
                    isGroupedUniqueKey = true;
                    break;
                }
            }
            if (isGroupedUniqueKey) break;
            for (String name : uniqueConstraint.fieldNames()) {
                if (name.equals(field.getSimpleName().toString())) {
                    isGroupedUniqueKey = true;
                    break;
                }
            }
        }

        if (isPrimaryKey || defaultValue != null)
            nullable = false;

        this.isForeignKey = this.joinColumn != null;
        this.isUniqueKey = isUniqueKey;
        this.isGroupedUniqueKey = isGroupedUniqueKey;
        this.nullable = nullable;
    }

    public boolean parseColumnCheck(Messager console) {
        if (isArray && !(type instanceof DeclaredType)) {
            console.printMessage(ERROR, "Primitive array is not supported: " + field.asType().toString() + ", use List or DeclaredType array instead.", field);
            return true;
        }

        if (joinColumn != null) {
            if (joinColumn.referencedColumnName().isEmpty() && joinColumn.referencedClassFieldName().isEmpty()) {
                console.printMessage(ERROR, "Least one of referencedColumnName and referencedFieldName should be provided for '@JoinTable'", field);
                return true;
            }
            if (!joinColumn.referencedColumnName().isEmpty() && !joinColumn.referencedClassFieldName().isEmpty()) {
                console.printMessage(ERROR, "Only one of referencedColumnName and referencedFieldName can be provided for '@JoinTable'", field);
                return true;
            }
            if (referencedTableClassPath == null) {
                console.printMessage(ERROR, "Field with @JoinColumn should assign referenced table class", field);
                return true;
            }
        }
        return false;
    }

    private String getColumnName(Element field) {
        if (column != null && !column.name().isEmpty())
            return column.name().toLowerCase();

        if (joinColumn != null) {
            if (!joinColumn.name().isEmpty()) {
                return joinColumn.name().toLowerCase();
            } else {
                return convertPropertyNameToUnderscoreName(field.getSimpleName().toString()).toLowerCase();
            }
        }

        return convertPropertyNameToUnderscoreName(field.getSimpleName().toString()).toLowerCase();
    }

    public boolean processTableJoin(Map<String, TableData> tableDataMap, TableData tableData, Messager console) {
        if (joinColumn == null) return false;
        TableData referencedTableData = tableDataMap.get(referencedTableClassPath);
        if (referencedTableData == null) {
            console.printMessage(ERROR, "Referenced table class '" + referencedTableClassPath + "' not found in the provided table data map.", field);
            return true;
        }

        // Find referenced ColumnInfo
        ColumnInfo referencedColumnInfo = null;
        if (!joinColumn.referencedColumnName().isEmpty()) {
            referencedColumnInfo = referencedTableData.tableColumns.get(joinColumn.referencedColumnName());
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced column '" + joinColumn.referencedColumnName() + "' not exist in table '" + referencedTableClassPath + "'", field);
                return true;
            }
        } else {
            referencedColumnInfo = referencedTableData.tableFields.get(joinColumn.referencedClassFieldName());
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced field '" + joinColumn.referencedClassFieldName() + "' not exist in table '" + referencedTableClassPath + "'", field);
                return true;
            }
        }
        this.referencedColumn = referencedColumnInfo;

        return false;
    }

    public String getQuotedSourceColumnName() {
        return quotedSourceColumnName;
    }

    public ColumnInfo getReferencedColumn() {
        return referencedColumn;
    }

    public String getForeignKeyName() {
        return tableInfo.name + '_' + columnName + "_FK";
    }
}
