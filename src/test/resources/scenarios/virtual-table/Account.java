package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = AccountRepository.class)
public record Account(
        @Id
        long id,

        String username,

        @Column(name = "DISPLAY_NAME")
        String displayName,

        long profileId
) {
}
