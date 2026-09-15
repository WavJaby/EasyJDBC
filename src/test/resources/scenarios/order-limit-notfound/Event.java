package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

import java.sql.Timestamp;

@Table(repositoryClass = EventRepository.class)
public record Event(
        @Id
        long id,

        String name,

        int priority,

        Timestamp happenedAt
) {
}
