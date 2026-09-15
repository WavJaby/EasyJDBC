package fx;

import com.wavjaby.jdbc.annotation.Select;

import java.util.List;

public interface AccountViewRepository {
    AccountView getById(long id);

    List<AccountView> getAll();

    List<AccountView> getByLocation(String location);

    @Select(field = "about")
    String getAboutById(long id);
}
