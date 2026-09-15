package fx;

import java.util.List;

public interface ParentRepository {
    Parent save(Parent row);

    Parent getById(long id);

    List<Parent> getAll();
}
