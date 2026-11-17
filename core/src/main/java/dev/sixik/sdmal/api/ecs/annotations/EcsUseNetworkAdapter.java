package dev.sixik.sdmal.api.ecs.annotations;

import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides one member's binary format with a cached no-argument network-only adapter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface EcsUseNetworkAdapter {
    /**
     * @return stateless adapter implementation specialized for this member's exact boxed type
     */
    Class<? extends EcsNetworkSerializer<?>> value();
}
