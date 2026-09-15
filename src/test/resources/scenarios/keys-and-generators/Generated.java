package fx;

import com.wavjaby.jdbc.annotation.GenericGenerator;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.util.Snowflake;

/** Snowflake id: the insert path must call the generator instead of binding the caller's value. */
@Table(repositoryClass = GeneratedRepository.class)
public record Generated(
        @Id
        @GenericGenerator(strategy = Snowflake.class)
        long id,

        String name
) {
}
