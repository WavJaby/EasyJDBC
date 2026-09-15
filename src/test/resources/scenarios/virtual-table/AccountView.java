package fx;

import com.wavjaby.jdbc.annotation.ColumnDefault;
import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/**
 * A view over Account joined to Profile: a straight passthrough column, a @FieldName rename, a @FieldName that also
 * names the table it reads from, and a COALESCE'd default -- the four shapes the view SELECT can take.
 */
@Table(virtual = true, virtualBaseClass = Account.class, repositoryClass = AccountViewRepository.class)
public record AccountView(
        long id,

        String username,

        @JoinColumn(referencedClass = Profile.class, referencedClassFieldName = "id")
        long profileId,

        @FieldName("bio")
        String about,

        @FieldName(value = "city", referencedClass = Profile.class)
        String location,

        @ColumnDefault("'anonymous'")
        String displayName
) {
}
