package dev.sixik.sdmal.api.ecs.annotations;

import dev.sixik.sdmal.api.ecs.ComponentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public concrete component with a public no-argument constructor for service discovery.
 * The ECS annotation processor writes its binary name to a service descriptor at compile time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EcsComponent {
    /**
     * Stable component identifier used by world archives.
     *
     * @return namespaced identifier such as {@code "yourmod:energy"}
     */
    String id();

    /**
     * Duplicate behavior for multiple instances on one object.
     *
     * @return attachment policy
     */
    ComponentType.DuplicatePolicy duplicatePolicy() default
            ComponentType.DuplicatePolicy.REJECT;
}
