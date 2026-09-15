package fx;

import java.util.List;

public interface CompositeRepository {
    Composite save(Composite row);

    Composite get(long leftId, long rightId);

    List<Composite> getAll();
}
