package com.wavjaby.jdbc.processor.resolve;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.processor.EmptyProcessingException;
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

    /** classPath -> resolved table, in {@code tables} order. Every table is resolved so each bad one reports; throws after the last when any failed. */
    public static Map<String, ResolvedTable> resolve(Map<String, TableData> tables, Messager console) throws EmptyProcessingException {
        Resolver resolver = new Resolver(tables, console);
        Map<String, ResolvedTable> resolved = new LinkedHashMap<>();
        boolean error = false;
        for (Map.Entry<String, TableData> entry : tables.entrySet()) {
            try {
                resolved.put(entry.getKey(), resolver.resolveTable(entry.getValue()));
            } catch (EmptyProcessingException e) {
                error = true;
            }
        }
        if (error)
            throw new EmptyProcessingException();
        return resolved;
    }

    private ResolvedTable resolveTable(TableData table) throws EmptyProcessingException {
        Map<ColumnInfo, ColumnInfo> references = new HashMap<>();
        for (ColumnInfo column : table.tableFields.values()) {
            if (column.joinColumn == null) continue;
            references.put(column, resolveReference(column));
        }

        TableData viewBase = null;
        Map<ColumnInfo, ResolvedTable.ViewSource> viewSources = Map.of();
        if (table.tableInfo.isVirtual) {
            TableInfo tableInfo = table.tableInfo;
            String virtualBaseClass = tableInfo.virtualBaseClass.toString();
            viewBase = tables.get(virtualBaseClass);
            if (viewBase == null) {
                console.printMessage(ERROR, "Virtual table base '" + virtualBaseClass + "' is not a table", tableInfo.tableClassEle, tableInfo.tableAnnMirror);
                throw new EmptyProcessingException();
            }
            viewSources = resolveViewSources(table, viewBase, references);
        }

        List<ForeignKeyGroup> foreignKeys = groupForeignKeys(table, references);

        return new ResolvedTable(table, references, foreignKeys, viewBase, viewSources);
    }

    private ColumnInfo resolveReference(ColumnInfo column) throws EmptyProcessingException {
        JoinColumn joinColumn = column.joinColumn;
        TableData referencedTableData = tables.get(column.referencedTableClassPath);
        if (referencedTableData == null) {
            console.printMessage(ERROR, "Referenced table class '" + column.referencedTableClassPath + "' not found in the provided table data map.", column.field);
            throw new EmptyProcessingException();
        }

        ColumnInfo referencedColumnInfo;
        if (!joinColumn.referencedColumnName().isEmpty()) {
            referencedColumnInfo = referencedTableData.tableColumns.get(ColumnInfo.foldColumnName(joinColumn.referencedColumnName()));
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced column '" + joinColumn.referencedColumnName() + "' not exist in table '" + column.referencedTableClassPath + "'", column.field);
                throw new EmptyProcessingException();
            }
        } else {
            referencedColumnInfo = referencedTableData.tableFields.get(joinColumn.referencedClassFieldName());
            if (referencedColumnInfo == null) {
                console.printMessage(ERROR, "Referenced field '" + joinColumn.referencedClassFieldName() + "' not exist in table '" + column.referencedTableClassPath + "'", column.field);
                throw new EmptyProcessingException();
            }
        }
        return referencedColumnInfo;
    }

    // A view column reads from the base table or from a table some @JoinColumn field of the view joins; @FieldName renames and may pin the table
    private Map<ColumnInfo, ResolvedTable.ViewSource> resolveViewSources(TableData table, TableData viewBase, Map<ColumnInfo, ColumnInfo> references) throws EmptyProcessingException {
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
                throw new EmptyProcessingException();
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
                throw new EmptyProcessingException();
            }
            viewSources.put(info, new ResolvedTable.ViewSource(targetColumn.tableData, targetColumn.columnName));
        }
        return viewSources;
    }

    /**
     * One FOREIGN KEY per @JoinColumn, or per JoinColumn.group: a group shares one referenced table and one ON UPDATE / ON DELETE, members in field order.
     * Each must reference the referenced table's primary key or one of its unique constraints -- PostgreSQL refuses anything else at CREATE TABLE.
     * A view gets none: its @JoinColumn is a JOIN, which may follow any column.
     */
    private List<ForeignKeyGroup> groupForeignKeys(TableData table, Map<ColumnInfo, ColumnInfo> references) throws EmptyProcessingException {
        if (table.tableInfo.isVirtual)
            return List.of();

        // An ungrouped column is keyed by itself, so it stays alone
        Map<Object, List<ColumnInfo>> members = new LinkedHashMap<>();
        for (ColumnInfo column : table.tableFields.values()) {
            if (references.get(column) == null) continue;
            String group = column.joinColumn.group();
            members.computeIfAbsent(group.isEmpty() ? column : group, k -> new ArrayList<>()).add(column);
        }

        List<ForeignKeyGroup> foreignKeys = new ArrayList<>();
        for (List<ColumnInfo> columns : members.values()) {
            ColumnInfo first = columns.getFirst();
            TableData referencedTable = references.get(first).tableData;
            JoinColumn.Action onUpdate = first.joinColumn.onUpdate(), onDelete = first.joinColumn.onDelete();
            List<String> sourceColumns = new ArrayList<>(), referencedColumns = new ArrayList<>();
            for (ColumnInfo column : columns) {
                ColumnInfo referenced = references.get(column);
                if (referenced.tableData != referencedTable) {
                    console.printMessage(ERROR, "@JoinColumn group '" + column.joinColumn.group() + "' references both '" + referencedTable.tableInfo.classPath +
                            "' and '" + referenced.tableData.tableInfo.classPath + "': one foreign key references one table", column.field);
                    throw new EmptyProcessingException();
                }
                if (column.joinColumn.onUpdate() != onUpdate || column.joinColumn.onDelete() != onDelete) {
                    console.printMessage(ERROR, "onUpdate/onDelete differ from the other columns of the composite foreign key to " + referencedTable.tableInfo.name, column.field);
                    throw new EmptyProcessingException();
                }
                sourceColumns.add(column.columnName);
                referencedColumns.add(referenced.columnName);
            }
            if (!isKey(referencedTable, referencedColumns)) {
                console.printMessage(ERROR, "Foreign key (" + String.join(",", sourceColumns) + ") references (" + String.join(",", referencedColumns) + ") of '" +
                        referencedTable.tableInfo.classPath + "', which is neither its primary key nor one of its unique constraints", first.field);
                throw new EmptyProcessingException();
            }
            foreignKeys.add(new ForeignKeyGroup(referencedTable.tableInfo, List.copyOf(referencedColumns), List.copyOf(sourceColumns), onUpdate, onDelete));
        }
        return foreignKeys;
    }

    // Exactly the primary key or one unique constraint, as a set; a column named twice is never a key
    private static boolean isKey(TableData table, List<String> columns) {
        Set<String> wanted = new HashSet<>(columns);
        if (wanted.size() != columns.size())
            return false;
        if (columnNames(table.primaryKey).equals(wanted))
            return true;
        for (List<ColumnInfo> uniqueKey : table.uniqueKeyList)
            if (columnNames(uniqueKey).equals(wanted))
                return true;
        return false;
    }

    private static Set<String> columnNames(List<ColumnInfo> columns) {
        Set<String> names = new HashSet<>();
        for (ColumnInfo column : columns)
            names.add(column.columnName);
        return names;
    }
}
