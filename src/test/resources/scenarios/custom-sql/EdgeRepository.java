package fx;

import com.wavjaby.jdbc.annotation.Count;
import com.wavjaby.jdbc.annotation.Delete;
import com.wavjaby.jdbc.annotation.Modifying;
import com.wavjaby.jdbc.annotation.QuerySQL;
import com.wavjaby.jdbc.annotation.Select;
import com.wavjaby.jdbc.annotation.Where;

import java.util.List;

public interface EdgeRepository {
    Edge save(Edge row);

    // literal-only predicate, no bind parameters
    @QuerySQL("ACCEPTED IS NULL")
    List<Edge> getPending();

    // :param substitution
    @QuerySQL("WEIGHT > :minWeight")
    List<Edge> getHeavierThan(int minWeight);

    // the same parameter bound twice
    @QuerySQL("LEFT_ID = :id OR RIGHT_ID = :id")
    List<Edge> getTouching(long id);

    // one parameter fanned out over two columns
    @QuerySQL("ACCEPTED = TRUE")
    List<Edge> getAcceptedFor(@Where({"leftId", "rightId"}) long id);

    // custom column expression, not a column name
    @QuerySQL("ACCEPTED = TRUE")
    @Select(columnSql = """
            CASE WHEN LEFT_ID = :id
                 THEN RIGHT_ID
                 ELSE LEFT_ID
            END""")
    List<Long> getNeighbourIds(@Where({"leftId", "rightId"}) long id);

    // non-default comparison operator and case folding
    @Select(field = "weight")
    List<Integer> getWeightsAbove(@Where(operation = ">") int weight);

    @QuerySQL("ACCEPTED IS NULL")
    boolean isPending(long leftId, long rightId);

    @Count
    @QuerySQL("ACCEPTED = TRUE AND (LEFT_ID = :id OR RIGHT_ID = :id)")
    int countAccepted(long id);

    @Modifying
    @QuerySQL("ACCEPTED IS NULL")
    Edge accept(@Where long leftId, @Where long rightId, Boolean accepted);

    @Delete
    @QuerySQL("ACCEPTED IS NULL")
    boolean deletePending(long leftId, long rightId);
}
