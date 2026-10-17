package fx;

import com.wavjaby.jdbc.annotation.FieldName;

/** @FieldName names a field Person does not have. */
public record Misnamed(long id, @FieldName("ageInYears") int years) {
}
