package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** One constructor of the right arity, parameters out of column order: the row mapper fills it by position. */
@Table(repositoryClass = ItemRepository.class)
public class Item {
    @Id
    long id;
    String name;

    public Item(String name, long id) {
        this.id = id;
        this.name = name;
    }
}
