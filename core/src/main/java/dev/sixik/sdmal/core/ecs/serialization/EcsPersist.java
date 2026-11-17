package dev.sixik.sdmal.core.ecs.serialization;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects an instance field or record component and assigns its stable, non-blank NBT key.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface EcsPersist {
    /**
     * @return explicit persistence key, independent of the Java member name
     */
    String value();
}
