package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = CategoryRepository.class)
public record Category(
        @Id
        long id,

        // not unique: PostgreSQL refuses a FK to it at CREATE TABLE
        String label
) {
}
