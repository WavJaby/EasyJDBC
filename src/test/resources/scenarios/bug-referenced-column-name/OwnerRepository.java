package fx;

public interface OwnerRepository {
    Owner save(Owner row);

    Owner getById(long id);
}
