package com.wavjaby.persistence;

import com.wavjaby.persistence.conf.Direction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface OrderBy {
    String field();

    Direction direction() default Direction.ASC;
}
