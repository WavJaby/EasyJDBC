package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Two FK columns to two different columns of one referenced table: they are inferred to be one composite constraint,
 * and both declare the same referential actions, which is what the conflict check requires.
 * <p>
 * Membership is inferred, never declared -- there is no way to say "these two columns are one FK, that third one is
 * its own". Two genuinely independent FKs to different columns of the same table land in this same branch.
 */
@Table(repositoryClass = BranchRepository.class)
public record Branch(
        @Id
        long id,

        @JoinColumn(referencedClass = Region.class, referencedClassFieldName = "countryId",
                onUpdate = JoinColumn.Action.CASCADE, onDelete = JoinColumn.Action.SET_NULL)
        long countryId,

        @JoinColumn(referencedClass = Region.class, referencedClassFieldName = "code",
                onUpdate = JoinColumn.Action.CASCADE, onDelete = JoinColumn.Action.SET_NULL)
        String regionCode,

        String name
) {
}
