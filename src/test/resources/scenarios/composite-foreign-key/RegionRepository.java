package fx;

public interface RegionRepository {
    Region save(Region row);

    Region getById(long id);
}
