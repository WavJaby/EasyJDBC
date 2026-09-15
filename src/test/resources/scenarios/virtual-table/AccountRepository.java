package fx;

import java.util.List;

public interface AccountRepository {
    Account save(Account row);

    Account getById(long id);

    List<Account> getAll();
}
