package fx;

import java.util.List;

public interface GeneratedRepository {
    Generated save(Generated row);

    int saveAll(List<Generated> rows);

    Generated getById(long id);
}
