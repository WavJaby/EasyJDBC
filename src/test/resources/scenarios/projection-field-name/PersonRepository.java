package fx;

import java.util.List;

public interface PersonRepository {
    PersonAge getAgeById(long id);

    List<PersonAge> getAgesByName(String name);
}
