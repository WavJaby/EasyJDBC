package com.wavjaby.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface JoinColumn {
    String name() default "";

    Class<?> referencedClass() default void.class;

    String referencedColumnName() default "";

    String referencedClassFieldName() default "";

    boolean unique() default false;

    boolean nullable() default false;

    boolean insertable() default true;

    boolean updatable() default true;

    String columnDefinition() default "";

    String table() default "";

//    ForeignKey foreignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
}
