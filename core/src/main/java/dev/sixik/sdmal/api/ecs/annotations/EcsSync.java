package dev.sixik.sdmal.api.ecs.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects an ordinary component field for direct binary network snapshots, independently of
 * persistence. On automatic network packets, every instance field is already selected and this
 * annotation optionally supplies a stable alias.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface EcsSync {
    /**
     * @return stable wire key, or empty to reuse EcsPersist; packets also fall back to the Java
     * field name
     */
    String value() default "";
}
