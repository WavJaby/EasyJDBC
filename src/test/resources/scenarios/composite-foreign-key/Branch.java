package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Two FK columns declared as one composite constraint by a shared group. Together they reference Region's (countryId, code) unique constraint,
 * and both declare the same referential actions, which one constraint requires.
 */
@Table(repositoryClass = BranchRepository.class)
public record Branch(
        @Id
        long id,

        @JoinColumn(referencedClass = Region.class, referencedClassFieldName = "countryId", group = "region",
                onUpdate = JoinColumn.Action.CASCADE, onDelete = JoinColumn.Action.SET_NULL)
        long countryId,

        @JoinColumn(referencedClass = Region.class, referencedClassFieldName = "code", group = "region",
                onUpdate = JoinColumn.Action.CASCADE, onDelete = JoinColumn.Action.SET_NULL)
        String regionCode,

        String name
) {
}
