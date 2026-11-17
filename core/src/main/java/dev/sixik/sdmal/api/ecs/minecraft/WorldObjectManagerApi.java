package dev.sixik.sdmal.api.ecs.minecraft;

import dev.sixik.sdmal.api.ecs.WorldObject;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Associates managers with world instances, keeping client/server and separate saves isolated. Keys
 * are weak, but components must not retain the world through the manager's value graph. Call
 * unregisterWorld on unload for deterministic cleanup. This API supplies storage only: callers
 * explicitly tick managers and send synchronization buffers.
 */
public final class WorldObjectManagerApi {
    /**
     * Weak world keys and live manager values; all map access is guarded by MANAGERS.
     */
    private static final Map<Level, WorldObjectManager> MANAGERS = new WeakHashMap<>();

    /**
     * Prevents utility instantiation.
     */
    private WorldObjectManagerApi() {}

    /**
     * Returns or creates one manager for this world instance.
     *
     * @param level world instance, non-null
     * @return live manager; repeated registration preserves its objects
     */
    public static WorldObjectManager registerWorld(Level level) {
        Objects.requireNonNull(level, "level");
        synchronized (MANAGERS) {
            WorldObjectManager existing = MANAGERS.get(level);
            if (existing != null && !existing.isClosed()) return existing;
            WorldObjectManager created = new WorldObjectManager();
            created.bindWorld(level);
            MANAGERS.put(level, created);
            return created;
        }
    }

    /**
     * Gets the manager of a registered world.
     *
     * @param level world instance, non-null
     * @return live manager, never null
     * @throws IllegalStateException if this world has no live registered manager
     */
    public static WorldObjectManager getManager(Level level) {
        synchronized (MANAGERS) {
            WorldObjectManager manager = MANAGERS.get(Objects.requireNonNull(level, "level"));
            if (manager == null || manager.isClosed())
                throw new IllegalStateException("World has no live ECS manager");
            return manager;
        }
    }

    /**
     * Resolves a manager by dimension and side when exactly one live world matches.
     *
     * @param dimensionKey dimension key, non-null
     * @param isClient true for a client world, false for a server world
     * @return uniquely matching live manager
     * @throws IllegalStateException if no manager or multiple world instances match
     */
    public static WorldObjectManager getManager(ResourceKey<Level> dimensionKey, boolean isClient) {
        Objects.requireNonNull(dimensionKey, "dimensionKey");
        WorldObjectManager found = null;
        synchronized (MANAGERS) {
            for (var entry : MANAGERS.entrySet()) {
                Level level = entry.getKey();
                if (level == null
                        || entry.getValue().isClosed()
                        || level.isClientSide() != isClient
                        || !level.dimension().equals(dimensionKey)) continue;
                if (found != null)
                    throw new IllegalStateException("Ambiguous dimension; use getManager(Level)");
                found = entry.getValue();
            }
        }
        if (found == null)
            throw new IllegalStateException("No live ECS manager for " + dimensionKey);
        return found;
    }

    /**
     * Removes and closes a world's manager, releasing every attached component.
     *
     * @param level unloaded world, non-null
     * @return true if a manager was registered
     * @throws IllegalStateException if called outside the world's owner thread; registration is
     *     retained
     * @throws dev.sixik.sdmal.api.ecs.WorldObjectException after cleanup if a detach callback fails
     */
    public static boolean unregisterWorld(Level level) {
        WorldObjectManager removed;
        synchronized (MANAGERS) {
            removed = MANAGERS.get(Objects.requireNonNull(level, "level"));
            if (removed != null) {
                removed.checkThread();
                MANAGERS.remove(level);
            }
        }
        if (removed == null) return false;
        removed.close();
        return true;
    }

    /**
     * Searches all registered worlds by UUID; prefer a specific manager when side matters.
     *
     * @param id object identifier, null returns empty
     * @return first matching live object, or empty
     */
    public static Optional<WorldObject> findObject(UUID id) {
        if (id == null) return Optional.empty();
        ArrayList<WorldObjectManager> snapshot;
        synchronized (MANAGERS) {
            snapshot = new ArrayList<>(MANAGERS.values());
        }
        for (WorldObjectManager manager : snapshot) {
            Optional<WorldObject> found = manager.get(id);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
}
