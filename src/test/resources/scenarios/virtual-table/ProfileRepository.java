package fx;

public interface ProfileRepository {
    Profile save(Profile row);

    Profile getById(long id);
}
