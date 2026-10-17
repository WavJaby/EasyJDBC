package fx;

import java.util.List;

/** Return shapes that were all one generic "is not acceptable" error. Parse reports every method of one repository, so they share a scenario. */
public interface PersonRepository {
    // a projection record without @Select: now a projection
    PersonName getNameById(long id);

    List<PersonName> getNamesByAge(int age);

    // a single-column type without @Select: an error that says to add @Select
    String getNameByAge(int age);

    // a supertype the table implements, as a list: an error that says to return the table or a projection
    List<Named> getAllByAge(int age);
}
