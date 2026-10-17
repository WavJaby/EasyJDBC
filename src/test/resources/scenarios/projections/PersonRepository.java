package fx;

import com.wavjaby.jdbc.annotation.Limit;
import com.wavjaby.jdbc.annotation.Order;

import java.util.List;

public interface PersonRepository {
    PersonName getNameById(long id);

    // two methods, one projection type: one shared extractor
    @Order(@Order.ByField("name"))
    @Limit(10)
    List<PersonName> getNamesByAge(int age);

    List<Contact> getContactsByAge(int age);
}
