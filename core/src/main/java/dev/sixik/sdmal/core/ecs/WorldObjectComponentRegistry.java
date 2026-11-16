package dev.sixik.sdmal.core.ecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of {@link WorldObjectComponentType}s, keyed by stable string id.
 *
 * <p>Fully concurrent: registration uses {@link ConcurrentHashMap#putIfAbsent} and
 * lookups never block. Registration is append-only during runtime; replacing an id
 * requires an explicit {@link #unregister(String)} so accidental codec collisions
 * surface loudly instead of silently swapping serializers.
 *
 * <p>Typical wiring: each component declares
 * {@code public static final WorldObjectComponentType<MyComp> TYPE = ...} and a static
 * initializer registers it once.
 */
public final class WorldObjectComponentRegistry {

    /**
     * Shared stateless instance used by the public codec or registry accessor.
     */
    private static final WorldObjectComponentRegistry INSTANCE = new WorldObjectComponentRegistry();

    /**
     * Concurrent registry indexed by stable unique component identifiers.
     */
    private final Map<String, WorldObjectComponentType<?>> byId = new ConcurrentHashMap<>();

    /**
     * Creates a WorldObjectComponentRegistry with the supplied configuration.
     */
    private WorldObjectComponentRegistry() {
    }

    /**
     * Process-wide singleton.
     * @return shared component registry, never null
     */
    public static WorldObjectComponentRegistry instance() {
        return INSTANCE;
    }

    /**
     * Registers a type; throws if the id is already taken by another instance.
     * @param <C> component type
     * @param type component descriptor used for typed membership and duplicate policies
     * @return registered descriptor; the supplied instance when newly registered
     */
    public <C extends WorldObjectComponent> WorldObjectComponentType<C> register(
            WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        WorldObjectComponentType<?> existing = byId.putIfAbsent(type.id(), type);
        if (existing != null && existing != type) {
            throw new IllegalStateException("Duplicate component id: " + type.id());
        }
        return type;
    }

    /**
     * Removes a registration. Returns the removed type, or empty if absent.
     * @param id stable object UUID or registry identifier, non-null
     * @return matching value, or Optional.empty() when absent
     */
    public Optional<WorldObjectComponentType<?>> unregister(String id) {
        return Optional.ofNullable(byId.remove(id));
    }

    /**
     * Looks up a type by id.
     * @param id stable object UUID or registry identifier, non-null
     * @return matching value, or Optional.empty() when absent
     */
    public Optional<WorldObjectComponentType<?>> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Looks up a type by id with a checked cast to the expected component class.
     * @param <C> component type
     * @param id stable object UUID or registry identifier, non-null
     * @param componentClass expected component class, non-null
     * @return matching value, or Optional.empty() when absent
     */
    @SuppressWarnings("unchecked")
    public <C extends WorldObjectComponent> Optional<WorldObjectComponentType<C>> get(
            String id, Class<C> componentClass) {
        WorldObjectComponentType<?> type = byId.get(id);
        if (type == null || !componentClass.isAssignableFrom(type.componentClass())) {
            return Optional.empty();
        }
        return Optional.of((WorldObjectComponentType<C>) type);
    }

    /**
     * Snapshot of all registered types.
     * @return immutable list of currently registered descriptors
     */
    public List<WorldObjectComponentType<?>> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * Number of registered types.
     * @return current membership count, greater than or equal to zero
     */
    public int size() {
        return byId.size();
    }
}
