package dev.sixik.sdmal.api.ecs;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stable descriptor of a component kind. Used for duplicate policies, lookups and debug output.
 *
 * <p>The id must be unique per registry (typically {@code "modid:path"}). The factory creates
 * detached instances; {@link WorldObject} attaches them via {@link
 * AbstractWorldObjectComponent#bind}.
 *
 * <p>Persistence may use {@link AnnotatedWorldObjectComponent} with ordinary annotated fields or
 * {@link FieldedWorldObjectComponent} with explicit field codecs. Network synchronization uses
 * {@code SyncedWorldObjectComponent} and revision-tracked fields.
 */
public final class WorldObjectComponentType<C extends WorldObjectComponent> {

    /**
     * What to do when an object already holds a component of this type.
     */
    public enum DuplicatePolicy {
        /**
         * Add as-is; several components of the same type may coexist.
         */
        ALLOW_MULTIPLE,
        /**
         * Reject with {@link DuplicateComponentException}.
         */
        REJECT,
        /**
         * Detach the old component and install the new one atomically.
         */
        REPLACE
    }

    /**
     * Stable object UUID or component registry identifier, never null.
     */
    private final String id;

    /**
     * Runtime class of components described by this type, never null.
     */
    private final Class<C> componentClass;

    /**
     * Policy applied when this type is already attached, never null.
     */
    private final DuplicatePolicy duplicatePolicy;

    /**
     * Supplier creating fresh detached instances of the component class.
     */
    private final Supplier<C> factory;

    /**
     * Cached inherited thread and tick-deadline restrictions.
     */
    private final int executionFlags;

    /**
     * Creates a WorldObjectComponentType with the supplied configuration.
     *
     * @param id              stable object UUID or registry identifier, non-null
     * @param componentClass  expected component class, non-null
     * @param duplicatePolicy policy for an already attached component of this type, non-null
     * @param factory         supplier of new detached component instances, non-null
     */
    public WorldObjectComponentType(
            String id,
            Class<C> componentClass,
            DuplicatePolicy duplicatePolicy,
            Supplier<C> factory) {
        this.id = Objects.requireNonNull(id, "id");
        this.componentClass = Objects.requireNonNull(componentClass, "componentClass");
        if (SnapshotTickableComponent.class.isAssignableFrom(componentClass)
                && TickableComponent.class.isAssignableFrom(componentClass))
            throw new IllegalArgumentException(
                    "Choose SnapshotTickableComponent or TickableComponent, not both");
        this.duplicatePolicy = Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        this.factory = Objects.requireNonNull(factory, "factory");
        executionFlags = EcsExecutionPolicy.flags(componentClass);
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    /**
     * Stable unique id, e.g. {@code "artisansdelight:energy"}.
     *
     * @return diagnostic or stable identifier text, never null
     */
    public String id() {
        return id;
    }

    /**
     * Runtime class of the component.
     *
     * @return runtime component class, never null
     */
    public Class<C> componentClass() {
        return componentClass;
    }

    /**
     * Duplicate handling when attaching to the same {@link WorldObject}.
     *
     * @return configured duplicate policy, never null
     */
    public DuplicatePolicy duplicatePolicy() {
        return duplicatePolicy;
    }

    /**
     * @return true when this component forbids worker-thread computation
     */
    public boolean requiresWorldThread() {
        return (executionFlags & EcsExecutionPolicy.WORLD_THREAD) != 0;
    }

    /**
     * @return true when scheduled work accessing this component cannot be deferred for budget
     * reasons
     */
    public boolean requiresCurrentTick() {
        return (executionFlags & EcsExecutionPolicy.CURRENT_TICK) != 0;
    }

    /**
     * Creates a detached instance.
     *
     * @return fresh detached component supplied by the factory, never null
     */
    public C createDefault() {
        return Objects.requireNonNull(factory.get(), "factory returned null");
    }

    /**
     * {@inheritDoc}
     *
     * @param other value to compare against, possibly null
     * @return true if the other object has the same descriptor or object identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorldObjectComponentType<?> that)) {
            return false;
        }
        return id.equals(that.id);
    }

    /**
     * {@inheritDoc}
     *
     * @return identifier hash; any signed 32-bit value
     */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * {@inheritDoc}
     *
     * @return diagnostic or stable identifier text, never null
     */
    @Override
    public String toString() {
        return "WorldObjectComponentType{id='" + id + "'}";
    }
}
