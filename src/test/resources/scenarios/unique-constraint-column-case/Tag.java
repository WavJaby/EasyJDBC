package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;
import com.wavjaby.jdbc.annotation.UniqueConstraint;

/** columnNames spelled as @Column(name) spells them; column names are case-folded, so the constraint's names must be too. */
@Table(repositoryClass = TagRepository.class, uniqueConstraints = {
        @UniqueConstraint(columnNames = {"TENANT_ID", "Label"})
})
public record Tag(
        @Id
        long id,

        @Column(name = "TENANT_ID")
        long tenantId,

        @Column(name = "Label")
        String label
) {
}
