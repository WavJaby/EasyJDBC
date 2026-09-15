package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

import java.util.List;

/** Array vs List vs byte[]: byte[] is BYTEA, the other two are ARRAY, and List is the branch that needs .size(). */
@Table(repositoryClass = BagRepository.class)
public record Bag(
        @Id
        long id,

        String[] tags,
        Long[] boxedIds,
        List<String> tagList,
        List<Long> idList,

        @Column(length = 8)
        String[] boundedTags,

        byte[] payload
) {
}
