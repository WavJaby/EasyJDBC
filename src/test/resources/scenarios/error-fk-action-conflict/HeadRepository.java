package fx;

public interface HeadRepository {
    Head save(Head row);

    Head getById(long id);
}
