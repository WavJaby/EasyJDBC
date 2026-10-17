package fx;

import com.wavjaby.jdbc.annotation.FieldName;

/** referencedClass picks a joined table in a view; a projection has only its repository's table. */
public record Pinned(long id, @FieldName(value = "age", referencedClass = Person.class) int years) {
}
