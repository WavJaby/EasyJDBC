package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.GenericGenerator;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.NotNull;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.util.Snowflake;

import java.sql.Timestamp;

@Table(repositoryClass = NoteRepository.class)
public record Note(
        @Id
        @GenericGenerator(strategy = Snowflake.class)
        long id,

        @NotNull
        String title,

        @Column(length = 512)
        String body,

        Timestamp createdAt,

        boolean archived,

        int revision
) {
}
