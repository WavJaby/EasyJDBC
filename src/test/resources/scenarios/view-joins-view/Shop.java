package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = ShopRepository.class)
public record Shop(
        @Id
        long id,

        long ownerId
) {
}
