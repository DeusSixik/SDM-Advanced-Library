package dev.sixik.sdmal.api.ecs;

import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Thread-safe base for {@link WorldObjectComponent} implementations.
 *
 * <p>State convention: guard mutable fields with {@link #withRead}/{@link #runWrite} (or the
 * exposed {@link #readLock}/{@link #writeLock} directly). Ownership is managed by {@link
 * WorldObject} only; the {@code bind} method is package-private on purpose.
 *
 * <p>Direct subclasses are rare: prefer {@code SyncedWorldObjectComponent} for shared multithreaded
 * state or {@link SingleThreadedWorldObjectComponent} for plain single-threaded logic (it
 * deliberately ignores these locks).
 */
public abstract class AbstractWorldObjectComponent implements WorldObjectComponent {

    /**
     * Read/write lock protecting state accessed through the base helpers.
     */
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    /**
     * Current owning object; null while detached.
     */
    private volatile WorldObject owner;

    /**
     * {@inheritDoc}
     *
     * @return matching value, or Optional.empty() when absent
     */
    @Override
    public final Optional<WorldObject> owner() {
        return Optional.ofNullable(owner);
    }

    /**
     * {@inheritDoc}
     *
     * @return true when an owner is currently bound
     */
    @Override
    public final boolean isAttached() {
        return owner != null;
    }

    /**
     * Returns the owner or throws {@link IllegalStateException} while detached.
     *
     * @return currently bound owner, never null
     * @throws IllegalStateException if this component has no owner
     */
    protected final WorldObject requireOwner() {
        WorldObject current = owner;
        if (current == null) {
            throw new IllegalStateException(getClass().getName() + " is detached");
        }
        return current;
    }

    /**
     * Runs {@code action} under the read lock and returns its result.
     *
     * @param <T> field or callback value type
     * @param action operation to execute under the documented lock or iteration contract
     * @return value returned by the supplied action, possibly null
     */
    protected final <T> T withRead(Supplier<T> action) {
        stateLock.readLock().lock();
        try {
            return action.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Runs {@code action} under the read lock.
     *
     * @param action operation to execute under the documented lock or iteration contract
     */
    protected final void runRead(Runnable action) {
        stateLock.readLock().lock();
        try {
            action.run();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Runs {@code action} under the write lock and returns its result.
     *
     * @param <T> field or callback value type
     * @param action operation to execute under the documented lock or iteration contract
     * @return value returned by the supplied action, possibly null
     */
    protected final <T> T withWrite(Supplier<T> action) {
        stateLock.writeLock().lock();
        try {
            return action.get();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * Runs {@code action} under the write lock.
     *
     * @param action operation to execute under the documented lock or iteration contract
     */
    protected final void runWrite(Runnable action) {
        stateLock.writeLock().lock();
        try {
            action.run();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * Direct read lock for hand-rolled critical sections.
     *
     * @return component state read lock, never null
     */
    protected final Lock readLock() {
        return stateLock.readLock();
    }

    /**
     * Direct write lock for hand-rolled critical sections.
     *
     * @return component state write lock, never null
     */
    protected final Lock writeLock() {
        return stateLock.writeLock();
    }

    /**
     * Called by {@link WorldObject} only. A {@code null} owner means detached.
     *
     * @param newOwner new owning object, or null when detaching
     */
    final synchronized void bind(WorldObject newOwner) {
        if (newOwner != null && owner != null) {
            throw new IllegalStateException(
                    getClass().getName() + " already attached to " + owner.id());
        }
        owner = newOwner;
    }
}
