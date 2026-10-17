package fx;

public interface TagRepository {
    Tag save(Tag row);

    Tag getById(long id);
}
