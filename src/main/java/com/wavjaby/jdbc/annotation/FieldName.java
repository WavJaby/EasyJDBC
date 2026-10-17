package com.wavjaby.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Source field name, when it differs from this one.
 * <p>
 * Method parameter: the table field it writes to.
 * Virtual table field: the base/joined table field the view column reads. {@code referencedClass} picks the table,
 * which must already be joined by a {@link JoinColumn} on some field.
 * Projection record component: the table field it reads; {@code referencedClass} does not apply.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface FieldName {
    String value();

    Class<?> referencedClass() default void.class;
}
