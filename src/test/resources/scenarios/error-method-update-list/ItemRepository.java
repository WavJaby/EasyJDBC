package fx;

import com.wavjaby.jdbc.annotation.Modifying;
import com.wavjaby.jdbc.annotation.Where;

import java.util.List;

public interface ItemRepository {
    // update returning the row reads it back by the WHERE columns: one row, never a list
    @Modifying
    List<Item> updateName(@Where long id, String name);
}
