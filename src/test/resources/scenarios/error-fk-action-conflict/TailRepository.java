package fx;

public interface TailRepository {
    Tail save(Tail row);

    Tail getById(long id);
}
