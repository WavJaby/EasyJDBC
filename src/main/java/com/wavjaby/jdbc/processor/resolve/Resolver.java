package com.wavjaby.jdbc.processor.resolve;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.TableData;
import com.wavjaby.jdbc.processor.model.TableInfo;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.type.DeclaredType;
import java.util.*;

import static com.wavjaby.jdbc.processor.util.AnnotationHelper.*;
import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * Stage B: links parsed tables to each other -- join targets, view column sources, foreign key groups.
 * Reads TableData / ColumnInfo and never writes them, so no table's result depends on which table was linked first.
 * Every cross-table error is reported here.
 */
public final class Resolver {
    private final Map<String, TableData> tables;
    private final Messager console;

    private Resolver(Map<String, TableData> tables, Messager console) {
        this.tables = tables;
        this.console = console;
    }

    /** classPath -> resolved table, in {@code tables} order; null when any table failed, each failure already reported. */
    public static Map<String, ResolvedTable> resolve(Map<String, TableData> tables, Messager console) {
        Resolver resolver = new Resolver(tables, console);
        Map<String, ResolvedTable> resolved = new LinkedHashMap<>();
        boolean allSuccess = true;
        for (Map.Entry<String, TableData> entry : tables.entrySet()) {
            ResolvedTable table = resolver.resolveTable(entry.getValue());
            if (table == null) allSuccess = false;
            else resolved.put(entry.getKey(), table);
        }
        return allSuccess ? resolved : null;
    }

    private ResolvedTable resolveTable(TableData table) {
        Map<ColumnInfo, ColumnInfo> references = new HashMap<>();
        for (ColumnInfo column : table.tableFields.values()) {
            if (column.joinColumn == null) continue;
            ColumnInfo referenced = resolveReference(column);
            if (referenced == null) return null;
            references.put(column, referenced);
        }

        TableData viewBase = null;
        Map<ColumnInfo, ResolvedTable.ViewSource> viewSources = Map.of();
        if (table.tableInfo.isVirtual) {
            TableInfo tableInfo = table.tableInfo;
            String virtualBaseClass = tableInfo.virtualBaseClass.toString();
            viewBase = tables.get(virtualBaseClass);
            if (viewBase == null) {
                console.printMessage(ERROR, "Virtual table base '" + virtualBaseClass + "' is not a table", tableInfo.tableClassEle, tableInfo.tableAnnMirror);
                return null;
            }
            viewSources = resolveViewSources(table, viewBase, references);
            if (viewSources == null) return null;
        }

        List<ForeignKeyGroup> foreignKeys = groupForeignKeys(table, references);
        if (foreignKeys == null) return null;

        return new ResolvedTable(table, references, foreignKeys, viewBase, viewSources);
    }

    private ColumnInfo resolveReference(ColumnInfo column) {
        JoinColumn joinColumn = column.joinColumn;
        TableData referencedTableData = tables.get(column.referencedTableClassPath);
        if (referencedTableData == null) {
            console.printMessage(ERROR, "Referenced table class '" + column.referencedTableClassPath + "' not found in the provided table data map.", column.field);
            return null;
        }

        ColumnInfo referencedColumnInfo;
        if (!joinColumn.referencedColumnName().isEmpty()) {
            referencedColumnInfo = referencedTableData.tableColumns.get(joinColumn.referencedColumnName());
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced column '" + joinColumn.referencedColumnName() + "' not exist in table '" + column.referencedTableClassPath + "'", column.field);
                return null;
            }
        } else {
            referencedColumnInfo = referencedTableData.tableFields.get(joinColumn.referencedClassFieldName());
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced field '" + joinColumn.referencedClassFieldName() + "' not exist in table '" + column.referencedTableClassPath + "'", column.field);
                return null;
            }
        }
        return referencedColumnInfo;
    }

    // A view column reads from the base table or from a table some @JoinColumn field of the view joins; @FieldName renames and may pin the table
    private Map<ColumnInfo, ResolvedTable.ViewSource> resolveViewSources(TableData table, TableData viewBase, Map<ColumnInfo, ColumnInfo> references) {
        String virtualBaseClass = table.tableInfo.virtualBaseClass.toString();

        // Get all referenced tables
        Map<String, TableData> referencedTables = new LinkedHashMap<>();
        referencedTables.put(virtualBaseClass, viewBase);
        for (ColumnInfo info : table.tableFields.values()) {
            if (info.joinColumn == null)
                continue;

            if (virtualBaseClass.equals(info.referencedTableClassPath)) {
                AnnotationMirror joinTableColumnMirror = getAnnotationMirror(info.field, JoinColumn.class);
                AnnotationValue referencedClass = getAnnotationValue(joinTableColumnMirror, "referencedClass");
                console.printMessage(ERROR, "Virtual table cannot reference itself", info.field, joinTableColumnMirror, referencedClass);
                return null;
            }

            referencedTables.put(info.referencedTableClassPath, references.get(info).tableData);
        }

        Map<ColumnInfo, ResolvedTable.ViewSource> viewSources = new HashMap<>();
        for (ColumnInfo info : table.tableFields.values()) {
            String sourceFieldName = info.field.getSimpleName().toString();
            String aliasTableClassPath = null;
            FieldName alias = info.field.getAnnotation(FieldName.class);
            if (alias != null) {
                if (!alias.value().trim().isEmpty())
                    sourceFieldName = alias.value().trim();
                DeclaredType aliasClass = getAnnotationValueClass(getAnnotationMirror(info.field, FieldName.class), "referencedClass");
                if (aliasClass != null)
                    aliasTableClassPath = aliasClass.toString();
            }

            ColumnInfo targetColumn = null;
            for (TableData referencedTable : referencedTables.values()) {
                if (aliasTableClassPath != null && !aliasTableClassPath.equals(referencedTable.tableInfo.classPath))
                    continue;
                targetColumn = referencedTable.tableFields.get(sourceFieldName);
                if (targetColumn != null) break;
            }
            if (targetColumn == null) {
                console.printMessage(ERROR, aliasTableClassPath == null
                                ? "Field '" + sourceFieldName + "' not found in any referenced table"
                                : "Field '" + sourceFieldName + "' not found in table '" + aliasTableClassPath + "'. A table is only referenced once some field joins it with '@JoinColumn'",
                        info.field);
                return null;
            }
            viewSources.put(info, new ResolvedTable.ViewSource(targetColumn.tableData, targetColumn.columnName));
        }
        return viewSources;
    }

    /**
     * Columns referencing different columns of one table share a composite constraint; two columns referencing the same column each get their own.
     * Membership is inferred, not declared: two unrelated single-column FKs to one table merge into one composite constraint.
     */
    private List<ForeignKeyGroup> groupForeignKeys(TableData table, Map<ColumnInfo, ColumnInfo> references) {
        List<GroupBuilder> groups = new ArrayList<>();
        for (ColumnInfo column : table.tableFields.values()) {
            ColumnInfo referencedColumn = references.get(column);
            if (referencedColumn == null) continue;

            boolean addToGroup = true;
            for (ColumnInfo target : table.tableFields.values()) {
                ColumnInfo otherReferencedColumn = references.get(target);
                if (otherReferencedColumn == null) continue;
                if (column == target)
                    continue;

                if (referencedColumn.tableInfo.equals(otherReferencedColumn.tableInfo) &&
                        referencedColumn.columnName.equals(otherReferencedColumn.columnName)) {
                    addToGroup = false;
                    break;
                }
            }

            // have duplicated target columns, create new group
            if (!addToGroup) {
                GroupBuilder group = new GroupBuilder(referencedColumn.tableInfo,
                        Collections.singletonList(referencedColumn.columnName), Collections.singletonList(column.columnName));
                group.applyActions(column.joinColumn);
                groups.add(group);
            } else {
                // Find group to add
                GroupBuilder foreignKeyGroup = null;
                for (GroupBuilder group : groups) {
                    if (group.referencedTable.name.equals(referencedColumn.tableInfo.name)) {
                        foreignKeyGroup = group;
                        break;
                    }
                }
                if (foreignKeyGroup == null) {
                    foreignKeyGroup = new GroupBuilder(referencedColumn.tableInfo, new ArrayList<>(), new ArrayList<>());
                    groups.add(foreignKeyGroup);
                }

                if (!foreignKeyGroup.applyActions(column.joinColumn)) {
                    console.printMessage(ERROR, "onUpdate/onDelete differ from the other columns of the composite foreign key to " + referencedColumn.tableInfo.name, column.field);
                    return null;
                }
                foreignKeyGroup.sourceColumns.add(column.columnName);
                foreignKeyGroup.referencedColumns.add(referencedColumn.columnName);
            }
        }
        return groups.stream().map(GroupBuilder::build).toList();
    }

    private static final class GroupBuilder {
        final TableInfo referencedTable;
        final List<String> referencedColumns;
        final List<String> sourceColumns;
        // null until the first column joins the group; every later column must declare the same actions
        JoinColumn.Action onUpdate, onDelete;

        GroupBuilder(TableInfo referencedTable, List<String> referencedColumns, List<String> sourceColumns) {
            this.referencedTable = referencedTable;
            this.referencedColumns = referencedColumns;
            this.sourceColumns = sourceColumns;
        }

        // false on conflict: one constraint carries one ON UPDATE / ON DELETE, so composite FK columns cannot disagree
        boolean applyActions(JoinColumn joinColumn) {
            if (onUpdate != null && (onUpdate != joinColumn.onUpdate() || onDelete != joinColumn.onDelete()))
                return false;
            onUpdate = joinColumn.onUpdate();
            onDelete = joinColumn.onDelete();
            return true;
        }

        ForeignKeyGroup build() {
            return new ForeignKeyGroup(referencedTable, referencedColumns, sourceColumns, onUpdate, onDelete);
        }
    }
}
