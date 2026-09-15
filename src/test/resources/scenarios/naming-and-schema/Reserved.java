package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/**
 * Schema, an explicit table name, camelCase -> snake_case, and columns whose names collide with PostgreSQL
 * reserved words / type-function names, which decide where quoting appears.
 */
@Table(name = "RESERVED_WORDS", schema = "app", repositoryClass = ReservedRepository.class)
public record Reserved(
        @Id
        long id,

        String user,
        String select,
        String order,
        String table,
        String between,
        String natural,
        String httpURLConnection,
        String alreadySnakeCase
) {
}
