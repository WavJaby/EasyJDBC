package fx;

import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;

/** A symmetric edge table: the shape that forces multi-column @Where and CASE expressions in custom SQL. */
@Table(repositoryClass = EdgeRepository.class,
        uniqueConstraints = @UniqueConstraint(fieldNames = {"leftId", "rightId"}))
public record Edge(
        long leftId,

        long rightId,

        Boolean accepted,

        int weight
) {
}
