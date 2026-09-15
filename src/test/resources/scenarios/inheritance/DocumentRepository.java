package fx;

import java.util.List;

public interface DocumentRepository {
    Document getById(long id);

    List<Document> getByTag(String tag);
}
