package fx;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

// @Id on the view: a key is declared, so only the view check stands between this and a FOREIGN KEY PostgreSQL rejects
@Table(virtual = true, virtualBaseClass = Owner.class, repositoryClass = OwnerCardRepository.class)
public record OwnerCard(
        @Id
        long id,

        @FieldName("nameRaw")
        String name
) {
}
