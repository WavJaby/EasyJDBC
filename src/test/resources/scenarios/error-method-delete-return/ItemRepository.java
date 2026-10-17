package fx;

import com.wavjaby.jdbc.annotation.Delete;

public interface ItemRepository {
    // @Delete only returns boolean / int / void; a row return passes the return-type check and is refused by delete alone
    @Delete
    Item deleteById(long id);
}
