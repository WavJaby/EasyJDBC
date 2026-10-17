package fx;

import java.util.List;

/** Return shapes rejected today. Parse reports every method of one repository, so they share a scenario. */
public interface PersonRepository {
    // a projection record without @Select
    PersonName getNameById(long id);

    List<PersonName> getNamesByAge(int age);

    // a single-column type without @Select
    String getNameByAge(int age);

    // a supertype the table implements, as a list -- the single-row form above is accepted
    List<Named> getAllByAge(int age);
}
