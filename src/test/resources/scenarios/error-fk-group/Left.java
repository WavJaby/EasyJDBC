package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = LeftRepository.class)
public record Left(
        @Id
        long id
) {
}
