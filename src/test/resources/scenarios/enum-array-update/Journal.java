package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = JournalRepository.class)
public record Journal(
        @Id
        long id,

        Mood[] moods
) {
}
