package com.wavjaby.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface QuerySQL {
    String value();

    String conjunction() default "and";

    boolean override() default false;
}
