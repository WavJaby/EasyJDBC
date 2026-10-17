package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = AccountRepository.class)
public record Account(
        @Id
        long id,

        @Column(unique = true)
        String code
) {
}
