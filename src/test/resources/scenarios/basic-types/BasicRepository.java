package fx;

import java.util.List;

public interface BasicRepository {
    Basic save(Basic row);

    Basic getById(long id);

    List<Basic> getAll();
}
