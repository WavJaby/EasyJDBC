package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.ColumnDefault;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.NotNull;
import com.wavjaby.jdbc.annotation.Table;

import java.math.BigDecimal;

/** Every @Column knob, plus the two other ways a column becomes NOT NULL. */
@Table(repositoryClass = OptionsRepository.class)
public record Options(
        @Id
        long id,

        @Column(name = "RENAMED_COLUMN")
        String renamed,

        @Column(length = 64)
        String bounded,

        @Column(nullable = false)
        String notNullByColumn,

        @NotNull
        String notNullByAnnotation,

        @Column(unique = true)
        String uniqueColumn,

        @Column(precision = 12, scale = 4)
        BigDecimal scaledDecimal,

        @Column(precision = 12)
        BigDecimal precisionOnlyDecimal,

        @Column(precision = 10, scale = 6)
        double doubleWithScale,

        @Column(columnDefinition = "TEXT")
        String rawDefinition,

        @Column(comment = "a comment")
        String commented,

        @Column(insertable = false)
        String notInsertable,

        @Column(updatable = false)
        String notUpdatable,

        @ColumnDefault("0")
        Integer defaulted,

        @ColumnDefault("'unset'")
        String defaultedText
) {
}
