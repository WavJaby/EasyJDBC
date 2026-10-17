package fx;

import com.wavjaby.jdbc.annotation.Select;

/**
 * Return shapes that compiled before parse classified return types, and generated code that failed at compile or run time.
 * All three are parse errors now.
 */
public interface PersonRepository {
    // a supertype the table implements: the generated List<Person> is not a List<Named>
    Named getById(long id);

    // a projection record forced through @Select(field): was generated as a single-column query
    @Select(field = "name")
    PersonName getNameById(long id);

    // @Select(field) whose column type differs from the return type: was a runtime conversion failure
    @Select(field = "name")
    Long getNameAsLongById(long id);
}
