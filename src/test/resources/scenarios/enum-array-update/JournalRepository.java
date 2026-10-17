package fx;

import com.wavjaby.jdbc.annotation.FieldName;
import com.wavjaby.jdbc.annotation.Modifying;
import com.wavjaby.jdbc.annotation.Where;

import java.util.List;

/**
 * Enum arrays bind as String[] copies (var0, var1, ...) declared once per method, in parameter order.
 * Here the WHERE array is declared first (var0) and the SET array second (var1): each placeholder must bind its own copy.
 */
public interface JournalRepository {
    @Modifying
    void replaceMoods(@Where(value = "moods") Mood[] oldMoods, @FieldName("moods") Mood[] newMoods);

    // a query, not an update: nothing declares the copy the WHERE binds
    List<Journal> getByMoods(Mood[] moods);
}
