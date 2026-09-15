package fx;

import com.wavjaby.jdbc.annotation.Delete;

import java.util.List;

public interface ChildRepository {
    Child save(Child row);

    Child getById(long id);

    List<Child> getByParentId(long parentId);

    @Delete
    void deleteById(long id);
}
