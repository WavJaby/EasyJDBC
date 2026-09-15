package fx;

import com.wavjaby.jdbc.annotation.Id;
import com.wavjaby.jdbc.annotation.Table;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;

/** Every scalar type the type mapper claims to know, one column each. */
@Table(repositoryClass = BasicRepository.class)
public record Basic(
        @Id
        long id,

        boolean flagPrimitive,
        Boolean flagBoxed,
        byte bytePrimitive,
        Byte byteBoxed,
        short shortPrimitive,
        Short shortBoxed,
        int intPrimitive,
        Integer intBoxed,
        long longBoxed,
        float floatPrimitive,
        Float floatBoxed,
        double doublePrimitive,
        Double doubleBoxed,
        char charPrimitive,
        Character charBoxed,

        String text,
        BigDecimal decimal,
        Date sqlDate,
        LocalDate localDate,
        Timestamp stamp,
        byte[] blob
) {
}
