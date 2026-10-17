package fx;

/** A second public constructor at the canonical arity: the row mapper could pick either. */
public record Swapped(long id, String name) {
    public Swapped(String name, long id) {
        this(id, name);
    }
}
