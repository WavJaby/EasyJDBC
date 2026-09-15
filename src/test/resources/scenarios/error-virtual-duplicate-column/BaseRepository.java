package fx;

public interface BaseRepository {
    Base save(Base row);

    Base getById(long id);
}
