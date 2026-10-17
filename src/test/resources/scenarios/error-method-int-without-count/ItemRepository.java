package fx;

public interface ItemRepository {
    // int return without @Count: not a count, and nothing else returns int
    int getNameLengthById(long id);
}
