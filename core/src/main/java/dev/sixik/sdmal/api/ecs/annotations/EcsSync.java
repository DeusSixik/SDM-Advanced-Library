package dev.sixik.sdmal.api.ecs.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects an ordinary component field for direct binary network snapshots, independently of
 * persistence.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface EcsSync {
    /**
     * @return stable wire key, or empty to reuse this member's EcsPersist key
     */
    String value() default "";
}
