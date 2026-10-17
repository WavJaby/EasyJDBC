package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** One group, two referenced tables: a FOREIGN KEY references one table. */
@Table(repositoryClass = MixedRepository.class)
public record Mixed(
        @Id
        long id,

        @JoinColumn(referencedClass = Left.class, referencedClassFieldName = "id", group = "pair")
        long leftId,

        @JoinColumn(referencedClass = Right.class, referencedClassFieldName = "id", group = "pair")
        long rightId
) {
}
