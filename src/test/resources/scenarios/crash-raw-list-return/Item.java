package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Crashes the processor today: a raw List return has no type argument and the return-type check reads argument 0.
 * Kept as the input that proves an internal exception surfaces as a compile error instead of vanishing -- replace it with another when this one is fixed.
 */
@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id
) {
}
