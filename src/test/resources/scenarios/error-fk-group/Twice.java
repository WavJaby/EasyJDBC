package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** One group naming Left.id twice: (id, id) is not a key, though the set {id} is. */
@Table(repositoryClass = TwiceRepository.class)
public record Twice(
        @Id
        long id,

        @JoinColumn(referencedClass = Left.class, referencedClassFieldName = "id", group = "pair")
        long firstLeftId,

        @JoinColumn(referencedClass = Left.class, referencedClassFieldName = "id", group = "pair")
        long secondLeftId
) {
}
