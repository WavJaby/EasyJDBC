package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = ProductRepository.class)
public record Product(
        @Id
        long id,

        @JoinColumn(referencedClass = Category.class, referencedClassFieldName = "label")
        String categoryLabel
) {
}
