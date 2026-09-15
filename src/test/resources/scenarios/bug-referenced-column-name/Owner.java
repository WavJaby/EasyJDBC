package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = OwnerRepository.class)
public record Owner(
        @Id
        long id,

        @Column(unique = true)
        String code
) {
}
