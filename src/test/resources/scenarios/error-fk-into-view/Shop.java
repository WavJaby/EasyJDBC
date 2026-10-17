package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** A table FK into a view: PostgreSQL refuses any FOREIGN KEY that references a view, so this must fail at compile time. */
@Table(repositoryClass = ShopRepository.class)
public record Shop(
        @Id
        long id,

        @JoinColumn(referencedClass = OwnerCard.class, referencedClassFieldName = "id")
        long ownerId
) {
}
