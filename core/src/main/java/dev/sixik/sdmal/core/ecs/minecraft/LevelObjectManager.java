package dev.sixik.sdmal.core.ecs.minecraft;

import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import net.minecraft.world.level.Level;

/** Optional bridge implemented by worlds exposing a WorldObjectManager. */
public interface LevelObjectManager {

    /**
     * Returns a world's bridge manager, falling back to the explicit registry.
     * @param level world, non-null
     * @return registered manager, never null
     * @throws IllegalStateException if no manager is registered
     */
    static WorldObjectManager getManager(Level level) {
        if (!(level instanceof LevelObjectManager manager)) {
            return WorldObjectManagerApi.getManager(level);
        }

        return manager.getManager();
    }

    /**
     * Returns the manager provided by this world bridge.
     * @return live manager, never null
     */
    WorldObjectManager getManager();
}
