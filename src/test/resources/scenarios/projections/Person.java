package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

@Table(repositoryClass = PersonRepository.class)
public record Person(
        @Id
        long id,

        String name,

        int age,

        String email
) {
}
