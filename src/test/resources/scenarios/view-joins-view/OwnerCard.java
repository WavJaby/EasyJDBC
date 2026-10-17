package fx;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.Table;

@Table(virtual = true, virtualBaseClass = Owner.class, repositoryClass = OwnerCardRepository.class)
public record OwnerCard(
        long id,

        @FieldName("nameRaw")
        String name
) {
}
