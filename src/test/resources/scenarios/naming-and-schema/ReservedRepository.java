package fx;

import java.util.List;

public interface ReservedRepository {
    Reserved save(Reserved row);

    Reserved getById(long id);

    List<Reserved> getByUser(String user);

    List<Reserved> getBySelect(String select);
}
