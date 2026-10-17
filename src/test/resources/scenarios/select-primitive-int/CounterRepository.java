package fx;

import com.wavjaby.jdbc.annotation.Select;

/** One column read into an int: names its column with @Select like any other single-column return. */
public interface CounterRepository {
    @Select(field = "hits")
    int getHitsById(long id);
}
