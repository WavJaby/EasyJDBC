package com.wavjaby.jdbc.annotation;

import com.wavjaby.jdbc.annotation.conf.Direction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface Order {
    ByField[] value();
    
    @interface ByField {
        String value();

        Direction direction() default Direction.ASC;
    }
}
