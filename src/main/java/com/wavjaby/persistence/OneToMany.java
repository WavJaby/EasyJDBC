package com.wavjaby.persistence;

import com.wavjaby.persistence.conf.FetchType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface OneToMany {
    Class<?> targetEntity() default void.class;

//    CascadeType[] cascade() default {};

    FetchType fetch() default FetchType.LAZY;

    String mappedBy() default "";

    boolean orphanRemoval() default false;
}
