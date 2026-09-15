package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * KNOWN BUG, snapshotted so the refactor has to decide about it deliberately.
 * <p>
 * referencedColumnName = "code" names a column of Owner, the referenced table. ColumnInfo.processTableJoin looks it up
 * in tableData.tableColumns -- this table, Item -- so the lookup only ever succeeds when both tables happen to carry a
 * column of that name, and then it resolves to the wrong one. The error message names the referenced table it never
 * searched. The referencedClassFieldName branch beside it uses the referenced table correctly.
 * <p>
 * Expected snapshot today: one ERROR and no generated DDL. A fix turns this into a working composite-free FK.
 */
@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id,

        @JoinColumn(referencedClass = Owner.class, referencedColumnName = "code")
        String ownerCode
) {
}
