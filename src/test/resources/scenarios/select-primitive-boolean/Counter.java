package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = CounterRepository.class)
public record Counter(
        @Id
        long id,

        int hits,

        boolean active
) {
}
