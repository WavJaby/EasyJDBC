package com.wavjaby.persistence;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface UniqueConstraint {
    String name() default "";

    String[] columnNames() default {};

    String[] fieldNames() default {};
}
