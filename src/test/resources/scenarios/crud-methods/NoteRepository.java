package fx;

import com.wavjaby.jdbc.annotation.Count;
import com.wavjaby.jdbc.annotation.Delete;
import com.wavjaby.jdbc.annotation.Modifying;
import com.wavjaby.jdbc.annotation.Select;
import com.wavjaby.jdbc.annotation.Where;

import java.sql.Timestamp;
import java.util.List;

/** One method per generated shape: insert, batch insert, single/list select, existence, update, delete, count. */
public interface NoteRepository {
    Note save(Note row);

    int saveAll(List<Note> rows);

    Note getById(long id);

    Note getByTitleAndArchived(String title, boolean archived);

    List<Note> getAll();

    List<Note> getByArchived(boolean archived);

    boolean existsById(long id);

    @Select(field = "title")
    String getTitleById(long id);

    @Select(field = "title")
    List<String> getAllTitles();

    @Modifying
    void updateBody(@Where long id, String body, Timestamp createdAt);

    @Modifying
    Note updateAndReturn(@Where long id, String title);

    @Delete
    void deleteById(long id);

    @Delete
    boolean deleteByTitle(String title);

    @Count
    int count();
}
