package fx;

/** A second projection type: gets its own extractor field. */
public record Contact(String email, String name) {
    // A constructor at another arity does not make the row mapper's pick ambiguous
    public Contact(String email) {
        this(email, null);
    }
}
