package com.wavjaby.jdbc.processor.resolve;

import com.wavjaby.jdbc.processor.model.ColumnInfo;
import com.wavjaby.jdbc.processor.model.TableData;

import java.util.List;
import java.util.Map;

/**
 * A parsed table plus everything that needed the other tables to work out.
 * Column maps are keyed by the parse-stage ColumnInfo instance: parse never replaces one, so identity is stable.
 */
public final class ResolvedTable {
    public final TableData table;
    public final List<ForeignKeyGroup> foreignKeys;
    public final TableData viewBase; // null unless the table is virtual

    private final Map<ColumnInfo, ColumnInfo> references;
    private final Map<ColumnInfo, ViewSource> viewSources;

    /** Where a view column reads from: {@code table.columnName}. */
    public record ViewSource(TableData table, String columnName) {
    }

    ResolvedTable(TableData table, Map<ColumnInfo, ColumnInfo> references, List<ForeignKeyGroup> foreignKeys,
                  TableData viewBase, Map<ColumnInfo, ViewSource> viewSources) {
        this.table = table;
        this.references = Map.copyOf(references);
        this.foreignKeys = List.copyOf(foreignKeys);
        this.viewBase = viewBase;
        this.viewSources = Map.copyOf(viewSources);
    }

    /** The column a @JoinColumn field points at; null for a field without @JoinColumn. */
    public ColumnInfo referencedColumn(ColumnInfo column) {
        return references.get(column);
    }

    /** Null unless the table is virtual. */
    public ViewSource viewSource(ColumnInfo column) {
        return viewSources.get(column);
    }
}
