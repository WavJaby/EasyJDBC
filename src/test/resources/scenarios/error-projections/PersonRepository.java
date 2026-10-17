package fx;

import com.wavjaby.jdbc.annotation.Modifying;
import com.wavjaby.jdbc.annotation.Where;

public interface PersonRepository {
    Nickname getNicknameById(long id);

    AgeText getAgeTextById(long id);

    Swapped getSwappedById(long id);

    // projections are for queries
    @Modifying
    IdOnly updateName(@Where long id, String name);
}
