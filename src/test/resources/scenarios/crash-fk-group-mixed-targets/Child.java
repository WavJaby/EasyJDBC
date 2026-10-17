package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Crashes the processor today: firstParentId and secondParentId share a target, so each gets a fixed-size one-column FK group;
 * parentCode targets another column of the same table, joins the first of those groups by table name, and adding to it throws.
 * Kept as the input that proves an internal exception surfaces as a compile error instead of vanishing.
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
