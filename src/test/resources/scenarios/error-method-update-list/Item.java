package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id,

        String name
) {
}
