package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** Two fields, only a no-arg constructor: the row mapper finds no constructor taking 2 and fails when the repository is built. */
@Table(repositoryClass = ItemRepository.class)
public class Item {
    @Id
    long id;
    String name;

    public Item() {
    }
}
