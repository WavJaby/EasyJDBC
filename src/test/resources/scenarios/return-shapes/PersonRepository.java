package fx;

import com.wavjaby.jdbc.annotation.Select;

import java.util.List;

/** Return shapes that compile today, whatever they generate. */
public interface PersonRepository {
    // a supertype the table implements, single row
    Named getById(long id);

    // a projection record forced through @Select(field): generated as a single-column query
    @Select(field = "name")
    PersonName getNameById(long id);

    // @Select(field) whose column type differs from the return type
    @Select(field = "name")
    Long getNameAsLongById(long id);
}
