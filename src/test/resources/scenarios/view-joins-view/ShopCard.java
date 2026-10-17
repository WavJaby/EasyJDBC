package fx;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * A view joining another view. The join target and the @FieldName lookup must resolve against OwnerCard as declared --
 * not against OwnerCard's own base table, and not differently depending on which view the processor links first.
 */
@Table(virtual = true, virtualBaseClass = Shop.class, repositoryClass = ShopCardRepository.class)
public record ShopCard(
        long id,

        @JoinColumn(referencedClass = OwnerCard.class, referencedClassFieldName = "id")
        long ownerId,

        @FieldName(value = "name", referencedClass = OwnerCard.class)
        String ownerName
) {
}
