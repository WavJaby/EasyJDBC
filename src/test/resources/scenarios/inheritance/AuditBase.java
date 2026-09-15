package fx;

import com.wavjaby.jdbc.annotation.Column;

import java.sql.Timestamp;

/** A non-annotated superclass: its fields still become columns, and a subclass field of the same name shadows it. */
public abstract class AuditBase {
    @Column(length = 64)
    String createdBy;

    Timestamp createdAt;

    // the subclass redeclares this one with a different @Column
    @Column(length = 16)
    String tag;
}
