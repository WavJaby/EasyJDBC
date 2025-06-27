package com.wavjaby.persistence;

import com.wavjaby.jdbc.util.IdentifierGenerator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface GenericGenerator {
    Class<? extends IdentifierGenerator> strategy();
}
