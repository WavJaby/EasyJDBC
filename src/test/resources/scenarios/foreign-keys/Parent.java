package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;

@Table(repositoryClass = ParentRepository.class, uniqueConstraints =
@UniqueConstraint(fieldNames = {"regionId", "code"}))
public record Parent(
        @Id
        long id,

        long regionId,

        @Column(unique = true)
        String code,

        String name
) {
}
