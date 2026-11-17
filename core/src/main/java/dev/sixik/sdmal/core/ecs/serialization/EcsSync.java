package dev.sixik.sdmal.core.ecs.serialization;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects an ordinary component field for direct binary network snapshots, independently of persistence.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface EcsSync {
    /**
     * @return stable wire key, or empty to reuse this member's EcsPersist key
     */
    String value() default "";
}
