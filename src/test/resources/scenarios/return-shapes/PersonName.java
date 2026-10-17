package fx;

/** A projection: a subset of Person's fields, same names and types, in its own order. */
public record PersonName(String name, long id) {
}
