package dev.sixik.sdmal.api.ecs.annotations;

import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides automatic type resolution for one persisted member with a cached, no-argument adapter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface EcsUseAdapter {
    /**
     * @return stateless adapter class implementing EcsSerializer for this member's exact boxed type
     */
    Class<? extends EcsSerializer<?>> value();
}
