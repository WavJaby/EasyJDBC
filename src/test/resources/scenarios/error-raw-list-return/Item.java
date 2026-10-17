package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** A raw List return names no row type. It once crashed the processor (type argument 0 of none); the internal-error path is now covered by FilerFaultTest. */
@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id
) {
}
