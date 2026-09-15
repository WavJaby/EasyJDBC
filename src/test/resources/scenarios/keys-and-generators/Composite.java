package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** Two @Id fields: the PK constraint has to carry both, in declaration order. */
@Table(repositoryClass = CompositeRepository.class)
public record Composite(
        @Id
        long leftId,

        @Id
        long rightId,

        @Column(length = 16)
        String label
) {
}
