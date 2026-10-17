package fx;

import com.wavjaby.jdbc.annotation.Column;
import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

/** Column order (superclass first) and which @Column wins for the shadowed `tag` are both what this pins down. */
@Table(repositoryClass = DocumentRepository.class)
public class Document extends AuditBase {
    @Id
    long id;

    String title;

    @Column(length = 128)
    String tag;

    // The row mapper fills this by position, in column order: superclass fields first, the shadowed tag where the superclass declared it
    public Document(String createdBy, java.sql.Timestamp createdAt, String tag, long id, String title) {
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.tag = tag;
        this.id = id;
        this.title = title;
    }
}
