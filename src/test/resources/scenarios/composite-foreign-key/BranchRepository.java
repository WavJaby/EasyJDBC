package fx;

import java.util.List;

public interface BranchRepository {
    Branch save(Branch row);

    Branch getById(long id);

    List<Branch> getByCountryId(long countryId);
}
