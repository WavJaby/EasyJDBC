package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = ProfileRepository.class)
public record Profile(
        @Id
        long id,

        // the view renames this one, so both names appear in the same SELECT
        String bio,

        @Column(length = 32)
        String city
) {
}
