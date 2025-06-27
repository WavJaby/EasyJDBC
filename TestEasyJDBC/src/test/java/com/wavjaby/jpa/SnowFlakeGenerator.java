package com.wavjaby.jpa;

import com.wavjaby.jdbc.util.Snowflake;
import org.hibernate.annotations.IdGeneratorType;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.EnumSet;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.hibernate.generator.EventTypeSets.INSERT_ONLY;

@IdGeneratorType(SnowFlakeGenerator.Generator.class)
@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface SnowFlakeGenerator {
    String name();

    class Generator implements BeforeExecutionGenerator {

        private final Snowflake snowflake;

        public Generator() {
            try {
                snowflake = new Snowflake();
            } catch (SocketException | UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
            return snowflake.nextId();
        }

        @Override
        public EnumSet<EventType> getEventTypes() {
            return INSERT_ONLY;
        }
    }
}
