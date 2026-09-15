package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

import java.util.List;

/** Both enum storage paths, plus the array/List forms that need the enum-to-String conversion. */
@Table(repositoryClass = TicketRepository.class)
public record Ticket(
        @Id
        long id,

        // native PostgreSQL enum type: no @Column
        Status status,

        // VARCHAR-backed: @Column(length) wins over the native type
        @Column(length = 32)
        Status statusText,

        Status[] statusArray,
        List<Status> statusList
) {
}
