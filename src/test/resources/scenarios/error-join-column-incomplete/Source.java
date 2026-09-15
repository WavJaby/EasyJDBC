package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** @JoinColumn naming neither referencedColumnName nor referencedClassFieldName: nothing to join on. */
@Table(repositoryClass = SourceRepository.class)
public record Source(
        @Id
        long id,

        @JoinColumn(referencedClass = Target.class)
        long targetId
) {
}
