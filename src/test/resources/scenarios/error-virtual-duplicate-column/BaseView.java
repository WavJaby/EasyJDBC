package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.Table;

/** Two view fields resolve to the same column name: rejected at compile time rather than by CREATE VIEW. */
@Table(virtual = true, virtualBaseClass = Base.class, repositoryClass = BaseViewRepository.class)
public record BaseView(
        long id,

        String title,

        @FieldName("subtitle")
        @Column(name = "TITLE")
        String alsoTitle
) {
}
