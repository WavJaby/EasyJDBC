package com.wavjaby.jdbc;

import com.wavjaby.persistence.UniqueConstraint;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Table {
    String name() default "";

    boolean virtual() default false;

    Class<?> virtualBaseClass() default void.class;

    Class<?> repositoryClass();

    String catalog() default "";

    String schema() default "";

    UniqueConstraint[] uniqueConstraints() default {};

//    Index[] indexes() default {};
}
