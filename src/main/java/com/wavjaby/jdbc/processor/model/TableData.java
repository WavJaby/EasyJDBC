package com.wavjaby.jdbc.processor.model;

import com.wavjaby.jdbc.Table;
import com.wavjaby.persistence.UniqueConstraint;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

public class TableData {
    public final TableInfo tableInfo;
    public final Map<String, String> classDependency = new HashMap<>();
    public final LinkedHashSet<String> tableColumnNames = new LinkedHashSet<>();
    public final LinkedHashMap<String, ColumnInfo> tableFields = new LinkedHashMap<>();
    public final List<MethodInfo> interfaceMethodInfo = new ArrayList<>();

    public final List<ColumnInfo> primaryKey = new ArrayList<>();
    public final List<List<ColumnInfo>> uniqueKeyList = new ArrayList<>();
    public final List<ForeignKeyGroup> foreignKeyList = new ArrayList<>();

    private TableData virtualBaseTableData;

    public record ForeignKeyGroup(TableInfo referencedTable, List<String> sourceColumns,
                                  List<String> referencedColumns) {
        ForeignKeyGroup(TableInfo targetTable) {
            this(targetTable, new ArrayList<>(), new ArrayList<>());
        }

        ForeignKeyGroup(TableInfo targetTable, String sourceColumn, String referencedColumn) {
            this(targetTable, Collections.singletonList(sourceColumn), Collections.singletonList(referencedColumn));
        }
    }

    public TableData(TableInfo tableInfo) {
        this.tableInfo = tableInfo;
    }

    public boolean parseTableField(Messager console) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        extractClassFields(tableInfo.tableClassEle, fields);

        // Get field with column info
        for (VariableElement field : fields.values()) {
            ColumnInfo info = new ColumnInfo(field, tableInfo);
            if (info.idGenerator != null) {
                String providerClassPath = info.idGenerator.toString();
                addDependency(providerClassPath);
            }

            if (info.parseColumnCheck(console))
                return true;

            tableFields.put(field.getSimpleName().toString(), info);
            if (tableColumnNames.contains(info.columnName)) {
                console.printMessage(ERROR, "Duplicate column name found: " + info.columnName, field);
                return true;
            }
            tableColumnNames.add(info.columnName);

            // Add primary key
            if (info.isPrimaryKey) {
                primaryKey.add(info);
            }

            // Add single unique key 
            if (info.isUniqueKey)
                uniqueKeyList.add(Collections.singletonList(info));
        }

        // Add grouped unique constraints
        AnnotationMirror constraintMirror = getAnnotationMirror(tableInfo.tableClassEle, Table.class);
        assert constraintMirror != null;
        List<? extends AnnotationValue> uniqueConstraints = getAnnotationValueList(constraintMirror, "uniqueConstraints");
        assert uniqueConstraints != null;
        int index = 0;
        for (UniqueConstraint uniqueConstraint : tableInfo.tableAnn.uniqueConstraints()) {
            AnnotationMirror uniqueConstraintMirror = getAnnotationMirrorFromValue(uniqueConstraints.get(index));
            if (uniqueConstraint.columnNames().length == 0 && uniqueConstraint.fieldNames().length == 0) {
                console.printMessage(ERROR, "Unique constraint must define at least one column or field", tableInfo.tableClassEle, uniqueConstraintMirror);
                return true;
            }
            List<ColumnInfo> uniqueKey = new ArrayList<>();
            // Fet unique constraint column by field name
            for (String name : uniqueConstraint.fieldNames()) {
                ColumnInfo columnInfo = this.tableFields.get(name);
                if (columnInfo == null) {
                    console.printMessage(ERROR, "Field '" + name + "' not exist in table '" + tableInfo.classPath + "'", tableInfo.tableClassEle, uniqueConstraintMirror);
                    return true;
                }
                uniqueKey.add(columnInfo);
            }
            // Fet unique constraint column by column name
            for (String name : uniqueConstraint.columnNames()) {
                if (!this.tableColumnNames.contains(name)) {
                    console.printMessage(ERROR, "Column '" + name + "' not exist in table: " + tableInfo.name, tableInfo.tableClassEle, uniqueConstraintMirror);
                    return true;
                }
                for (ColumnInfo columnInfo : this.tableFields.values()) {
                    if (!columnInfo.columnName.equals(name)) continue;
                    uniqueKey.add(columnInfo);
                    break;
                }
            }
            uniqueKeyList.add(uniqueKey);
            index++;
        }

        return false;
    }

    public boolean parseRepoMethods(TableData tableData, Messager console) {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        extractClassMethods(tableInfo.repoIntClassElement, methods);

        // Find all methods in interface
        for (ExecutableElement e : methods.values()) {
            MethodInfo methodInfo = new MethodInfo(e, tableData);
            if (methodInfo.parseMethod(console)) return true;
            interfaceMethodInfo.add(methodInfo);
        }

        return false;
    }

    /**
     * Process {@code @JoinColumn} annotation class link, column foreign key
     */
    public boolean processTableJoin(TableData tableData, Map<String, TableData> tableDataMap, Messager console) {
        for (ColumnInfo column : tableData.tableFields.values()) {
            // Prepare referencedColumnInfo and referencedTableData
            if (column.processTableJoin(tableDataMap, this, console))
                return true;
        }

        for (ColumnInfo column : tableData.tableFields.values()) {
            if (column.getReferencedColumnInfo() == null) continue;
            ColumnInfo referencedColumn = column.getReferencedColumnInfo();

            boolean addToGroup = true;
            for (ColumnInfo target : tableData.tableFields.values()) {
                if (target.getReferencedColumnInfo() == null) continue;
                if (column == target)
                    continue;

                ColumnInfo otherReferencedColumn = target.getReferencedColumnInfo();
                if (referencedColumn.tableInfo.fullname.equals(otherReferencedColumn.tableInfo.fullname) &&
                        referencedColumn.columnName.equals(otherReferencedColumn.columnName))
                    addToGroup = false;
            }

            // have duplicated target columns, create new group
            if (!addToGroup) {
                this.foreignKeyList.add(new ForeignKeyGroup(referencedColumn.tableInfo, column.columnName, referencedColumn.columnName));
            } else {
                // Find group to add
                ForeignKeyGroup foreignKeyGroup = null;
                for (ForeignKeyGroup group : this.foreignKeyList) {
                    if (group.referencedTable.name.equals(referencedColumn.tableInfo.name)) {
                        foreignKeyGroup = group;
                        break;
                    }
                }
                if (foreignKeyGroup == null) {
                    foreignKeyGroup = new ForeignKeyGroup(referencedColumn.tableInfo);
                    this.foreignKeyList.add(foreignKeyGroup);
                }
                foreignKeyGroup.sourceColumns.add(column.columnName);
                foreignKeyGroup.referencedColumns.add(referencedColumn.columnName);
            }
        }
        return false;
    }

    public boolean processVirtualTable(TableData tableData, Map<String, TableData> tableDataMap, Messager console) {
        TableInfo tableInfo = tableData.tableInfo;
        String virtualBaseClass = tableInfo.virtualBaseClass.toString();
        TableData virtualBaseTableData = tableDataMap.get(virtualBaseClass);
        if (virtualBaseTableData == null) {
            console.printMessage(ERROR, "Virtual table base '" + virtualBaseClass + "' is not a table", tableInfo.tableClassEle, tableInfo.tableAnnMirror);
            return true;
        }
        this.virtualBaseTableData = virtualBaseTableData;
        return false;
    }

    public void addDependency(String classPath) {
        String className = classPath.substring(classPath.lastIndexOf('.') + 1);
        String fieldName = Character.toLowerCase(className.charAt(0)) + className.substring(1);
        classDependency.put(classPath, fieldName);
    }

    public void addDependency(String classPath, String fieldName) {
        classDependency.put(classPath, fieldName);
    }

    public String getDependencyFieldName(String string) {
        return classDependency.get(string);
    }

    public Map<String, String> getDependencies() {
        return classDependency;
    }

    public TableData getVirtualBaseTableData() {
        return virtualBaseTableData;
    }
}
