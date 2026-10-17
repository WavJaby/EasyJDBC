package fx;

import java.util.List;

public interface ItemRepository {
    // insert returns the row it wrote, never a list
    List<Item> save(Item row);
}
