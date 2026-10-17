package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = RightRepository.class)
public record Right(
        @Id
        long id
) {
}
