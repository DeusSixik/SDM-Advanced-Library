package dev.sixik.sdmal.api.ecs;

/**
 * Observer of structural and error events of one {@link WorldObject}.
 *
 * <p>Callbacks run outside the owner's write lock on the mutating thread; keep them short and
 * non-blocking. Synchronous structural mutations are safe but never affect an in-flight tick or
 * callback iteration &mdash; use {@link WorldObject#defer} when work must run on the ticking thread
 * or after the current mutation settles.
 *
 * <p>All methods have no-op defaults so listeners subscribe only to what they need.
 */
public interface WorldObjectListener {

    /**
     * A component became visible to readers.
     *
     * @param owner world object whose membership changed
     * @param component component instance, non-null
     */
    default void onComponentAdded(WorldObject owner, WorldObjectComponent component) {}

    /**
     * A component was hidden from new readers.
     *
     * @param owner world object whose membership changed
     * @param component component instance, non-null
     */
    default void onComponentRemoved(WorldObject owner, WorldObjectComponent component) {}

    /**
     * Component order changed (priority insert, reorder, swap).
     *
     * @param owner world object whose membership changed
     */
    default void onOrderChanged(WorldObject owner) {}

    /**
     * A component tick threw; ticking of the remaining components continues.
     *
     * @param owner world object whose membership changed
     * @param component component instance, non-null
     * @param cause original failure to preserve in the exception
     */
    default void onComponentError(
            WorldObject owner, WorldObjectComponent component, Throwable cause) {}
}
