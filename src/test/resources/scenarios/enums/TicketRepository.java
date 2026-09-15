package fx;

import com.wavjaby.jdbc.annotation.Count;
import com.wavjaby.jdbc.annotation.Delete;
import com.wavjaby.jdbc.annotation.Modifying;
import com.wavjaby.jdbc.annotation.QuerySQL;
import com.wavjaby.jdbc.annotation.Select;
import com.wavjaby.jdbc.annotation.Where;

import java.util.List;

public interface TicketRepository {
    Ticket save(Ticket row);

    Ticket getById(long id);

    // enum as a WHERE parameter: the bind site needs the ::type cast
    List<Ticket> getByStatus(Status status);

    List<Ticket> getByStatusText(Status statusText);

    @Select(field = "status")
    Status getStatusById(long id);

    @Select(field = "statusList")
    List<Status> getStatusListById(long id);

    @Modifying
    void updateStatus(@Where long id, Status status);

    @QuerySQL("STATUS = :status")
    List<Ticket> findByCustomSql(Status status);

    @Count
    int count();

    @Delete
    void deleteById(long id);
}
