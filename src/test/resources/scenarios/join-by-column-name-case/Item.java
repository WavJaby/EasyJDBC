package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** referencedColumnName spelled as the referenced @Column(name) spells it; column names are case-folded, so the reference must be too. */
@Table(repositoryClass = ItemRepository.class)
public record Item(
        @Id
        long id,

        @JoinColumn(referencedClass = Owner.class, referencedColumnName = "OWNER_CODE")
        String ownerCode
) {
}
