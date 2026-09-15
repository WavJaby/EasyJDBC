package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * The referencedColumnName branch of @JoinColumn: the FK names a column of the referenced table, not a field of it.
 * It resolved against the wrong table until 2026-09-15, so this scenario is what keeps it resolved against Owner.
 */
@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id,

        @JoinColumn(referencedClass = Owner.class, referencedColumnName = "code")
        String ownerCode
) {
}
