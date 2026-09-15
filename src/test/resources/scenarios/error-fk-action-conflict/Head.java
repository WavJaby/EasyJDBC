package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;

@Table(repositoryClass = HeadRepository.class,
        uniqueConstraints = @UniqueConstraint(fieldNames = {"groupId", "slug"}))
public record Head(
        @Id
        long id,

        long groupId,

        String slug
) {
}
