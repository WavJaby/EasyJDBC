package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** int[] is not byte[]: primitive arrays other than byte have no mapping, so the processor must say so. */
@Table(repositoryClass = MatrixRepository.class)
public record Matrix(
        @Id
        long id,

        int[] values
) {
}
