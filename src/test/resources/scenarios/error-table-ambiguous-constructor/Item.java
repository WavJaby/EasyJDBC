package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** A second public constructor at the canonical arity: the row mapper takes whichever reflection lists first. */
@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id,

        String name
) {
    public Item(String name, long id) {
        this(id, name);
    }
}
