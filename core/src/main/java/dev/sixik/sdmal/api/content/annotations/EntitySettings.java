package dev.sixik.sdmal.api.content.annotations;

import net.minecraft.world.entity.MobCategory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional native EntityType settings for a class annotated with RegisterContent.
 * This configures the type; natural spawning, spawn eggs, and AI remain gameplay decisions.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EntitySettings {
    /**
     * @return native spawning/category classification
     */
    MobCategory category() default MobCategory.MISC;

    /**
     * @return positive finite bounding-box width, in blocks
     */
    float width() default 0.6f;

    /**
     * @return positive finite bounding-box height, in blocks
     */
    float height() default 1.8f;

    /**
     * @return nonnegative native client tracking range, in chunks
     */
    int trackingRange() default 5;

    /**
     * @return positive synchronization interval, in game ticks
     */
    int updateInterval() default 3;

    /**
     * @return whether the native entity type is immune to fire
     */
    boolean fireImmune() default false;
}
