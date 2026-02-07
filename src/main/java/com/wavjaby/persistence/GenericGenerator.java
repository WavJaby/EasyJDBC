package com.wavjaby.persistence;

import com.wavjaby.jdbc.util.IdentifierGenerator;
import com.wavjaby.jdbc.util.Snowflake;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface GenericGenerator {
    Class<? extends IdentifierGenerator> strategy() default Snowflake.class;
}
