package fx;

import com.wavjaby.jdbc.annotation.FieldName;

/** A component renamed from the table field it reads: @FieldName names the field, the component keeps its own name. */
public record PersonAge(long id, @FieldName("age") int years) {
}
