package fx;

import com.wavjaby.jdbc.annotation.Select;

/** A boolean with @Select reads the named column; without @Select a boolean return asks whether a row exists. */
public interface CounterRepository {
    @Select(field = "active")
    boolean isActiveById(long id);

    boolean existsById(long id);
}
