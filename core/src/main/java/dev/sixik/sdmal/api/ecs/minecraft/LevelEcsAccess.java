package dev.sixik.sdmal.api.ecs.minecraft;

import dev.sixik.sdmal.api.ecs.EcsObjectManager;

import net.minecraft.world.level.Level;

/**
 * Optional bridge implemented by worlds exposing a EcsObjectManager.
 */
public interface LevelEcsAccess {

    /**
     * Returns a world's bridge manager, falling back to the explicit registry.
     *
     * @param level world, non-null
     * @return registered manager, never null
     * @throws IllegalStateException if no manager is registered
     */
    static EcsObjectManager getManager(Level level) {
        if (!(level instanceof LevelEcsAccess manager)) {
            return LevelEcsManagers.getManager(level);
        }

        return manager.getManager();
    }

    /**
     * Returns the manager provided by this world bridge.
     *
     * @return live manager, never null
     */
    EcsObjectManager getManager();
}
