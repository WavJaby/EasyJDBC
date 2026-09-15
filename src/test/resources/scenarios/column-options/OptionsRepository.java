package fx;

import java.util.List;

public interface OptionsRepository {
    Options save(Options row);

    Options getById(long id);

    List<Options> getAll();
}
