package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Three ungrouped FKs into one table, two sharing a target column: three single-column constraints.
 * Membership used to be inferred, and this mix crashed the inference (a fixed-size group was added to).
 */
@Table(repositoryClass = ChildRepository.class)
public record Child(
        @Id
        long id,

        @JoinColumn(referencedClass = Parent.class, referencedClassFieldName = "id")
        long firstParentId,

        @JoinColumn(referencedClass = Parent.class, referencedClassFieldName = "id")
        long secondParentId,

        @JoinColumn(referencedClass = Parent.class, referencedClassFieldName = "code")
        String parentCode
) {
}
