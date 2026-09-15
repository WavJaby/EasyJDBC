package fx;

import com.wavjaby.jdbc.annotation.Select;

import java.util.List;

public interface BagRepository {
    Bag save(Bag row);

    Bag getById(long id);

    @Select(field = "tags")
    List<String[]> getAllTags();

    @Select(field = "tags")
    String[] getTagsById(long id);

    @Select(field = "tagList")
    List<String> getTagListById(long id);
}
