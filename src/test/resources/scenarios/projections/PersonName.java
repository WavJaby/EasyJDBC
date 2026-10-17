package fx;

/** Components out of table order on purpose: SELECT must follow the components, not the table. */
public record PersonName(String name, long id) {
}
