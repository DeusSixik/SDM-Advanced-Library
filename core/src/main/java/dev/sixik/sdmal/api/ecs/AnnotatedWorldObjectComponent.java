package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.ecs.serialization.NbtSerialization;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Persists ordinary fields selected by EcsPersist without requiring Field handles or per-field
 * codecs. Private/inherited fields and EcsData records are supported by a cached runtime schema.
 * Serialization checks the owning manager thread and uses the base state lock. Mutators must follow
 * the same owner-thread/lock discipline; Java field assignments are not intercepted or
 * revision-tracked. EcsSync fields can be sent directly through FriendlyByteBuf using full binary
 * snapshots. Schema evolution continues to use {@link #dataVersion()} and {@link #migrateNbt(int,
 * CompoundTag)}.
 */
public abstract class AnnotatedWorldObjectComponent extends AbstractWorldObjectComponent
        implements PersistentWorldObjectComponent {
    /**
     * Writes all annotated fields, preserving unknown top-level payload keys.
     *
     * @param nbt owned destination compound, non-null
     * @throws IllegalStateException                                           if called outside the owning manager's thread
     * @throws dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException for invalid schemas
     *                                                                         or values
     */
    @Override
    public final void writeNbt(CompoundTag nbt) {
        checkPersistenceThread();
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
    @Override
    public final void readNbt(CompoundTag nbt) {
        checkPersistenceThread();
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
    public final void writeNetwork(FriendlyByteBuf buffer) {
        checkPersistenceThread();
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
    public final void readNetwork(FriendlyByteBuf buffer) {
        checkPersistenceThread();
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
    public final long networkSchemaFingerprint() {
        return NetworkSerialization.schemaFingerprint(getClass());
    }

    /**
     * Requires the world owner thread while attached; standalone access is coordinated by the
     * caller.
     *
     * @throws IllegalStateException if the owning manager rejects the current thread
     */
    private void checkPersistenceThread() {
        owner().flatMap(WorldObject::manager).ifPresent(WorldObjectManager::checkThread);
    }
}
