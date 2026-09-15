package fx;

import com.wavjaby.jdbc.annotation.Limit;
import com.wavjaby.jdbc.annotation.NotFound;
import com.wavjaby.jdbc.annotation.Order;
import com.wavjaby.jdbc.annotation.QuerySQL;
import com.wavjaby.jdbc.annotation.Select;
import com.wavjaby.jdbc.annotation.conf.Direction;

import java.util.List;
import java.util.NoSuchElementException;

/** ORDER BY / LIMIT placement, and the throw-on-empty path. */
public interface EventRepository {
    Event save(Event row);

    @Order(@Order.ByField("happenedAt"))
    List<Event> getAllOldestFirst();

    @Order({@Order.ByField(value = "priority", direction = Direction.DESC),
            @Order.ByField(value = "happenedAt", direction = Direction.ASC)})
    List<Event> getAllRanked();

    @Limit(10)
    List<Event> getFirstTen();

    @Order(@Order.ByField(value = "happenedAt", direction = Direction.DESC))
    @Limit(1)
    Event getLatest();

    @Order(@Order.ByField(value = "priority", direction = Direction.DESC))
    @Limit(5)
    @QuerySQL("PRIORITY > :minPriority")
    List<Event> getTopAbove(int minPriority);

    @NotFound(exception = NoSuchElementException.class, args = "\"no event \" + id")
    Event getByIdOrThrow(long id);

    @Select(field = "name")
    @NotFound(exception = NoSuchElementException.class)
    String getNameOrThrow(long id);
}
