package com.wavjaby.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface Where {
    String[] value() default {};

    String operation() default "=";
    
    boolean ignoreCase() default false;
}
