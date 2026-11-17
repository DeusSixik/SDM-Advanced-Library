package dev.sixik.sdmal.api.ecs;

import java.util.Optional;

/**
 * Single unit of data or behaviour attached to a {@link WorldObject}.
 *
 * <p>Two ready-made bases cover both models: {@code SyncedWorldObjectComponent} for shared
 * multithreaded state with NBT persistence and network deltas, and {@link
 * SingleThreadedWorldObjectComponent} for plain single-threaded logic with direct NBT persistence
 * and no locking overhead.
 *
 * <p>Lifecycle contract: {@link #onAttached} runs after the component becomes visible to readers.
 * Synchronous structural changes are allowed; use {@link WorldObject#defer} to schedule follow-up
 * work on the ticking thread. {@link #onDetached} runs after the component is hidden from new
 * readers, so stale readers may still observe it until they refresh their view.
 */
public interface WorldObjectComponent {

    /**
     * Lower values tick and serialize first. Defaults to {@code 0}.
     *
     * @return integer priority; lower values tick first
     */
    default int priority() {
        return 0;
    }

    /**
     * Called once, after the component becomes visible to readers.
     */
    default void onAttached() {
    }

    /**
     * Called once, after the component has been detached and hidden from new readers.
     */
    default void onDetached() {
    }

    /**
     * Owning object, or {@link Optional#empty} while detached.
     *
     * @return matching value, or Optional.empty() when absent
     */
    Optional<WorldObject> owner();

    /**
     * Returns {@code true} between {@link #onAttached} and {@link #onDetached}.
     *
     * @return true when an owner is currently bound
     */
    boolean isAttached();
}
