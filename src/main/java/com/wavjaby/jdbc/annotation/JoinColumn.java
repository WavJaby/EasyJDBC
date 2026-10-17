package com.wavjaby.jdbc.annotation;

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

    /**
     * FK referential action. Declare CASCADE where the referenced key is editable or the row is derived from the parent.
     */
    enum Action {NO_ACTION, CASCADE, SET_NULL}

    Action onUpdate() default Action.NO_ACTION;

    Action onDelete() default Action.NO_ACTION;

    /**
     * Composite FK membership. Fields of one table sharing a group form one FOREIGN KEY, in field order: same referenced table, same actions,
     * and together they must reference the referenced table's primary key or one of its unique constraints. Empty -> a single-column FK of its own.
     */
    String group() default "";

    String columnDefinition() default "";

    String table() default "";

//    ForeignKey foreignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
}
