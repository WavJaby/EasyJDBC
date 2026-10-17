package fx;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.Table;

/**
 * A view whose base is itself a view: columns resolve against OwnerCard as declared (its renamed `name`, not Owner's name_raw),
 * and owner_card must be created before badge whatever order the processor sees the classes in (Badge sorts first).
 */
@Table(virtual = true, virtualBaseClass = OwnerCard.class, repositoryClass = BadgeRepository.class)
public record Badge(
        long id,

        @FieldName("name")
        String label
) {
}
