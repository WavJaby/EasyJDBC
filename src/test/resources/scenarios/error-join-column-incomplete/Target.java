package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = TargetRepository.class)
public record Target(
        @Id
        long id
) {
}
