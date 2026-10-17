package com.wavjaby.jdbc.processor.resolve;

import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.processor.model.TableInfo;

import java.util.List;

/** One FOREIGN KEY constraint: source columns of this table -> referenced columns of {@code referencedTable}, positionally paired. */
public record ForeignKeyGroup(TableInfo referencedTable, List<String> referencedColumns, List<String> sourceColumns,
                              JoinColumn.Action onUpdate, JoinColumn.Action onDelete) {
}
