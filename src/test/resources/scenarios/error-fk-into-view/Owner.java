package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = OwnerRepository.class)
public record Owner(
        @Id
        long id,

        String nameRaw
) {
}
