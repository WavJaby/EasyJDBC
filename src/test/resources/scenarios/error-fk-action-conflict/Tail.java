package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.JoinColumn;
import com.wavjaby.jdbc.annotation.Table;

/** Two columns of one inferred composite FK declaring different actions: one constraint cannot carry both. */
@Table(repositoryClass = TailRepository.class)
public record Tail(
        @Id
        long id,

        @JoinColumn(referencedClass = Head.class, referencedClassFieldName = "groupId",
                onDelete = JoinColumn.Action.CASCADE)
        long groupId,

        @JoinColumn(referencedClass = Head.class, referencedClassFieldName = "slug",
                onDelete = JoinColumn.Action.SET_NULL)
        String slug
) {
}
