package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/**
 * An enum with @Column but no length. Two rules decide "native enum type?" for it and they disagree:
 * the column type takes the native enum (only @Column(length) switches to VARCHAR), while the CREATE TYPE and the ::type bind cast are skipped (any @Column switches them off).
 */
@Table(repositoryClass = DiaryRepository.class)
public record Diary(
        @Id
        long id,

        @Column(nullable = false)
        Mood mood
) {
}
