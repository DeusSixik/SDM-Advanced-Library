package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.sync.NetworkComponent;

import dev.sixik.sdmal.core.ecs.serialization.NbtSerialization;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Default base for components attached to an EcsObject, including world-independent application data.
 * Use ordinary fields with EcsPersist for persistence and EcsSync for binary snapshots; no tick,
 * Level, position, or network connection is required. Add TickableComponent or
 * SnapshotTickableComponent when the component actually needs periodic work.
 *
 * <p>Ownership and lifecycle are managed by EcsObject. Managed mutations belong to the manager's
 * thread; call checkOwnerThread in mutators. Lock helpers coordinate explicitly shared state, but
 * do not grant worker jobs access to live components. Field assignments are not intercepted and
 * EcsSync does not schedule delivery or track revisions. NetworkComponent adds explicit tracked
 * fields and network deltas when those are needed.
 */
public abstract class Component {


    /**
     * Read/write lock protecting state accessed through the base helpers.
     */
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    /**
     * Current owning object; null while detached.
     */
    private volatile EcsObject owner;

    /**
     * Returns the object that currently owns this component.
     *
     * @return matching value, or Optional.empty() when absent
     */
    public final Optional<EcsObject> owner() {
        return Optional.ofNullable(owner);
    }

    /**
     * Reports whether this component is currently attached to an object.
     *
     * @return true when an owner is currently bound
     */
    public final boolean isAttached() {
        return owner != null;
    }

    /**
     * Returns the owner or throws {@link IllegalStateException} while detached.
     *
     * @return currently bound owner, never null
     * @throws IllegalStateException if this component has no owner
     */
    protected final EcsObject requireOwner() {
        EcsObject current = owner;
        if (current == null) {
            throw new IllegalStateException(getClass().getName() + " is detached");
        }
        return current;
    }

    /**
     * Runs {@code action} under the read lock and returns its result.
     *
     * @param <T>    field or callback value type
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
     * @param <T>    field or callback value type
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
     * Called by {@link EcsObject} only. A {@code null} owner means detached.
     *
     * @param newOwner new owning object, or null when detaching
     */
    final synchronized void bind(EcsObject newOwner) {
        if (newOwner != null && owner != null) {
            throw new IllegalStateException(
                    getClass().getName() + " already attached to " + owner.id());
        }
        owner = newOwner;
    }

    /**
     * @return priority in ascending tick order; equal priorities retain attachment order
     */
    public int priority() {
        return 0;
    }

    /**
     * Runs after attachment has become visible to sibling components and listeners.
     */
    public void onAttached() {
    }

    /**
     * Runs after this component has been removed from its owner's visible composition.
     */
    public void onDetached() {
    }

    /**
     * Checks the owning manager's thread and rejects live state access from worker compute.
     * Unmanaged objects use caller-coordinated access and have no implicit thread affinity.
     *
     * @throws IllegalStateException if the attached manager rejects the current thread
     */
    protected final void checkOwnerThread() {
        EcsObject current = owner;
        if (current == null) return;
        current.manager().ifPresent(EcsObjectManager::checkThread);
    }

    /**
     * @return current persisted schema version; override and migrate when changing stored data
     */
    public int dataVersion() {
        return 1;
    }

    /**
     * Migrates an older component payload before ownership callbacks run.
     * Version-zero stateless components from the former ownership-only base retain unknown keys.
     *
     * @param savedVersion non-negative older schema version
     * @param nbt          independently owned saved payload
     * @return migrated current-version payload
     * @throws EcsPersistenceException if an explicit migration is required
     */
    public CompoundTag migrateNbt(int savedVersion, CompoundTag nbt) {
        if (savedVersion == 0 && dataVersion() == 1 && !(this instanceof NetworkComponent)
                && !NbtSerialization.hasPersistedFields(getClass())) return nbt;
        throw new EcsPersistenceException(
                "No migration from component version " + savedVersion + " to " + dataVersion());
    }

    /**
     * Writes all annotated fields, preserving unknown top-level payload keys.
     *
     * @param nbt owned destination compound, non-null
     * @throws IllegalStateException                                           if called outside the owning manager's thread
     * @throws dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException for invalid schemas
     *                                                                         or values
     */
    public void writeNbt(CompoundTag nbt) {
        checkOwnerThread();
        readLock().lock();
        try {
            NbtSerialization.writeComponent(this, nbt);
        } finally {
            readLock().unlock();
        }
    }

    /**
     * Stages all present values before installing them; absent keys retain initialized defaults.
     *
     * @param nbt source payload, non-null and never mutated
     * @throws IllegalStateException                                           if called outside the owning manager's thread
     * @throws dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException for malformed or
     *                                                                         incompatible data
     */
    public void readNbt(CompoundTag nbt) {
        checkOwnerThread();
        writeLock().lock();
        try {
            NbtSerialization.readComponent(this, nbt);
        } finally {
            writeLock().unlock();
        }
    }

    /**
     * Writes a complete length-delimited binary snapshot of EcsSync fields, without constructing
     * NBT. This does not schedule delivery or maintain delta revisions. Writer index is restored on
     * failure.
     *
     * @param buffer destination packet, owned by the caller
     * @throws IllegalStateException                                           if called outside the owning manager thread
     * @throws dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException for invalid wire
     *                                                                         schemas/values
     */
    public void writeNetwork(FriendlyByteBuf buffer) {
        checkOwnerThread();
        readLock().lock();
        try {
            NetworkSerialization.writeComponent(this, buffer);
        } finally {
            readLock().unlock();
        }
    }

    /**
     * Stages one matching binary frame before applying selected fields; other fields remain
     * untouched. The packet must be dispatched onto the target world thread before this call.
     *
     * @param buffer source packet; following frames remain unread and reader index rolls back on
     *               failure
     * @throws IllegalStateException                                           if called outside the owning manager thread
     * @throws dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException for
     *                                                                         malformed/mismatched frames
     */
    public void readNetwork(FriendlyByteBuf buffer) {
        checkOwnerThread();
        writeLock().lock();
        try {
            NetworkSerialization.readComponent(this, buffer);
        } finally {
            writeLock().unlock();
        }
    }

    /**
     * @return deterministic binary schema fingerprint, also carried and checked inside each network
     * frame
     */
    public long networkSchemaFingerprint() {
        return NetworkSerialization.schemaFingerprint(getClass());
    }

}
