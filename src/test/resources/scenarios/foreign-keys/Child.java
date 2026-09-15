package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Two independent single-column FKs to the same referenced column, one of them nullable. Table ordering in the DDL
 * also matters -- the child must be created after the parent.
 * referencedColumnName lives in its own scenario: it does not work today (see bug-referenced-column-name).
 * Composite FKs and referential actions live in composite-foreign-key.
 */
@Table(repositoryClass = ChildRepository.class)
public record Child(
        @Id
        long id,

        @JoinColumn(referencedClass = Parent.class, referencedClassFieldName = "id")
        long parentId,

        @JoinColumn(referencedClass = Parent.class, referencedClassFieldName = "id", nullable = true)
        Long optionalParentId,

        String note
) {
}
