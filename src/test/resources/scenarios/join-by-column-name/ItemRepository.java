package fx;

public interface ItemRepository {
    Item save(Item row);

    Item getById(long id);
}
