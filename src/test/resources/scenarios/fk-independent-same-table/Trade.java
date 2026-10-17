package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** Two unrelated FKs into one table, at different columns: the buyer by id, the seller by code. Each is its own constraint. */
@Table(repositoryClass = TradeRepository.class)
public record Trade(
        @Id
        long id,

        @JoinColumn(referencedClass = Account.class, referencedClassFieldName = "id")
        long buyerId,

        @JoinColumn(referencedClass = Account.class, referencedClassFieldName = "code")
        String sellerCode
) {
}
