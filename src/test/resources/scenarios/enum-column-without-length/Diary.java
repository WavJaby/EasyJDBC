package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/**
 * An enum with @Column but no length stays a native enum type: column type, CREATE TYPE and ::type bind cast must agree.
 * Until 2026-10-17 two rules decided this and disagreed -- the column got the enum type while CREATE TYPE and the cast were skipped.
 */
@Table(repositoryClass = DiaryRepository.class)
public record Diary(
        @Id
        long id,

        @Column(nullable = false)
        Mood mood,

        // columnDefinition: the user owns the column type, so no CREATE TYPE and no ::type cast
        @Column(columnDefinition = "VARCHAR(16)")
        Mood moodText
) {
}
