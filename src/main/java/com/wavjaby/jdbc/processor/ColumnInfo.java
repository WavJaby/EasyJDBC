package com.wavjaby.jdbc.processor;

import com.wavjaby.persistence.*;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.Map;

import static com.wavjaby.jdbc.processor.AnnotationHelper.*;
import static com.wavjaby.jdbc.util.StringConverter.convertPropertyNameToUnderscoreName;
import static javax.tools.Diagnostic.Kind.ERROR;

public class ColumnInfo {
    public final Column column;
    public final TableInfo tableInfo;

    public final boolean isString;
    public final boolean isArray;
    public final boolean isEnum;
    public final VariableElement field;
    public final String columnName;
    public final boolean nullable;
    public final boolean isPrimaryKey;
    public final boolean isUniqueKey;
    public final boolean isForeignKey;
    public final Object defaultValue;

    public final DeclaredType idGenerator;

    public final JoinColumn joinColumn;
    public final String referencedTableClassPath;
    private TableData referencedTableData;
    private ColumnInfo referencedColumnInfo;

    public ColumnInfo(VariableElement field, TableInfo tableInfo) {
        this.column = field.getAnnotation(Column.class);
        this.tableInfo = tableInfo;

        // Get GeneratedValue
        AnnotationMirror GeneratedValue = getAnnotationMirror(field, GenericGenerator.class);
        if (GeneratedValue != null) {
            AnnotationValue strategy = getAnnotationValue(GeneratedValue, "strategy");
            assert strategy != null;
            this.idGenerator = (DeclaredType) strategy.getValue();
//            if (strategy == null) {
//                console.printMessage(ERROR, "Filed to get id provider for column '" + columnInfo.columnName + "'", columnInfo.forField);
//                return true;
//            }
        } else
            this.idGenerator = null;

        // Get JoinColumn
        this.joinColumn = field.getAnnotation(JoinColumn.class);
        if (this.joinColumn != null) {

            // Check if annotation assign referenced class
            AnnotationMirror joinTableColumnMirror = getAnnotationMirror(field, JoinColumn.class);
            Element referencedClass = getAnnotationValueClass(joinTableColumnMirror, "referencedClass");
            if (referencedClass != null)
                this.referencedTableClassPath = referencedClass.toString();
            else
                this.referencedTableClassPath = null;
        } else
            this.referencedTableClassPath = null;

        boolean isString = false;
        boolean isArray = false;
        boolean isEnum = false;
        TypeMirror type = field.asType();
        if (type.getKind() == TypeKind.ARRAY) {
            isArray = true;
            type = ((ArrayType) type).getComponentType();
        }
        if (type.getKind() == TypeKind.DECLARED) {
            isString = type.toString().equals(String.class.getName());
            isEnum = ((DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
        }
        this.isString = isString;
        this.isArray = isArray;
        this.isEnum = isEnum;

        // Get column info
        boolean nullable = field.getAnnotation(NotNull.class) == null;
        this.isPrimaryKey = field.getAnnotation(Id.class) != null;
        ColumnDefault columnDefault = field.getAnnotation(ColumnDefault.class);
        this.defaultValue = columnDefault == null || columnDefault.value().isBlank() ? null : columnDefault.value();
        this.field = field;
        this.columnName = getColumnName(field);

        boolean isUniqueKey = false;
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
        for (UniqueConstraint uniqueConstraint : tableInfo.tableAnn.uniqueConstraints()) {
            for (String name : uniqueConstraint.columnNames()) {
                if (name.equals(columnName)) {
                    isUniqueKey = true;
                    break;
                }
            }
            if (isUniqueKey) break;
            for (String name : uniqueConstraint.fieldNames()) {
                if (name.equals(field.getSimpleName().toString())) {
                    isUniqueKey = true;
                    break;
                }
            }
        }

        if (isPrimaryKey || defaultValue != null)
            nullable = false;

        this.isForeignKey = this.joinColumn != null;
        this.isUniqueKey = isUniqueKey;
        this.nullable = nullable;
    }

    public boolean parseColumnCheck(Messager console) {
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
            return column.name();

        if (joinColumn != null) {
            if (!joinColumn.name().isEmpty()) {
                return joinColumn.name();
            } else {
                return convertPropertyNameToUnderscoreName(field.getSimpleName().toString());
            }
        }

        return convertPropertyNameToUnderscoreName(field.getSimpleName().toString());
    }

    public boolean processTableJoin(Map<String, TableData> tableDataMap, TableData tableData, Messager console) {
        if (joinColumn == null) return false;
        TableData refrenceTableData = tableDataMap.get(referencedTableClassPath);
        if (refrenceTableData == null) {
            console.printMessage(ERROR, "Referenced table class '" + referencedTableClassPath + "' not found in the provided table data map.", field);
            return true;
        }

        // Find referenced ColumnInfo
        ColumnInfo referencedColumnInfo = null;
        if (!joinColumn.referencedColumnName().isEmpty()) {
            for (ColumnInfo info : refrenceTableData.tableFields.values()) {
                if (info.columnName.equals(joinColumn.referencedColumnName())) {
                    referencedColumnInfo = info;
                    break;
                }
            }
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced column '" + joinColumn.referencedColumnName() + "' not exist in table '" + referencedTableClassPath + "'", field);
                return true;
            }
        } else {
            referencedColumnInfo = refrenceTableData.tableFields.get(joinColumn.referencedClassFieldName());
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced field '" + joinColumn.referencedClassFieldName() + "' not exist in table '" + referencedTableClassPath + "'", field);
                return true;
            }
        }
        this.referencedTableData = refrenceTableData;
        this.referencedColumnInfo = referencedColumnInfo;
        tableData.addDependency(referencedColumnInfo.tableInfo.repoIntClassPath);

        return false;
    }

    public ColumnInfo getReferencedColumnInfo() {
        return referencedColumnInfo;
    }

    public TableData getReferencedTableData() {
        return referencedTableData;
    }

    public String getForeignKeyName() {
        return tableInfo.name + '_' + columnName + "_FK";
    }
}
