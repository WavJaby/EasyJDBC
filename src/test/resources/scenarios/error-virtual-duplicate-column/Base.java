package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = BaseRepository.class)
public record Base(
        @Id
        long id,

        String title,

        String subtitle
) {
}
