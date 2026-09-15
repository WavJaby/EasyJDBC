package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;

@Table(repositoryClass = RegionRepository.class,
        uniqueConstraints = @UniqueConstraint(fieldNames = {"countryId", "code"}))
public record Region(
        @Id
        long id,

        long countryId,

        String code
) {
}
