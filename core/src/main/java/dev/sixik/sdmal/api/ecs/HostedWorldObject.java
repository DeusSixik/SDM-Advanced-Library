package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.sync.SyncedWorldObjectComponent;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lifecycle and single-owner persistence for an ECS object hosted by a BlockEntity or another chunk
 * object. The host stores {@link #save()} in its own data. Active membership is always transient in
 * the world archive. All operations belong to the thread that constructed this handle; no worker
 * may call lifecycle methods.
 */
public final class HostedWorldObject {
    /**
     * Host lifecycle thread, also required for manager registration and persistence.
     */
    private final Thread ownerThread = Thread.currentThread();

    /**
     * Last independent snapshot, retained even when component types cannot currently be resolved.
     */
    private CompoundTag saved;

    /**
     * Original archive metadata for preservation of unknown extension fields.
     */
    private WorldObjectArchive archive = WorldObjectArchive.empty();

    /**
     * Active object, or null while unloaded or quarantined.
     */
    private volatile WorldObject object;

    /**
     * Active world manager, or null while unbound.
     */
    private WorldObjectManager manager;

    /**
     * Diagnostic from the last failed restore; null after successful binding.
     */
    private String restoreFailure;

    /**
     * Upper bound for one full hosted binary snapshot, including metadata and all component
     * payloads.
     */
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;

    /**
     * Encodes a full client composition with direct buffer formats and selected synchronized fields
     * only. Type identifiers and attachment sequences preserve repeated components. Mode zero
     * reconstructs defaults.
     *
     * @param output caller-owned destination buffer; writer index rolls back on encoding failure
     */
    public void writeNetwork(FriendlyByteBuf output) {
        checkThread();
        WorldObject current =
                object().orElseThrow(() -> new IllegalStateException("Hosted object is inactive"));
        int start = output.writerIndex();
        try {
            output.writeInt(0);
            output.writeVarInt(1);
            output.writeUUID(current.id());
            var entries = current.entries();
            if (entries.size() > 4096) throw new WorldObjectException("Too many hosted components");
            output.writeVarInt(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.type() == null
                        || WorldObjectComponentRegistry.instance()
                        .get(entry.type().id())
                        .orElse(null)
                        != entry.type())
                    throw new WorldObjectException(
                            "Hosted synchronization requires registered component types");
                output.writeUtf(entry.type().id(), 1024);
                output.writeVarLong(entry.sequence());
                if (entry.component() instanceof AnnotatedWorldObjectComponent annotated) {
                    output.writeByte(1);
                    annotated.writeNetwork(output);
                } else if (entry.component() instanceof SyncedWorldObjectComponent tracked) {
                    output.writeByte(2);
                    tracked.writeFull(output);
                } else output.writeByte(0);
                if (output.writerIndex() - start > MAX_SNAPSHOT_BYTES)
                    throw new WorldObjectException("Hosted binary snapshot exceeds 8 MiB");
            }
            output.setInt(start, output.writerIndex() - start - 4);
        } catch (Throwable failure) {
            output.writerIndex(start);
            EcsFailures.rethrowFatal(failure);
            throw failure;
        }
    }

    /**
     * Decodes into a new unpublished composition before replacing an active client mirror. No
     * saved/private fields are copied; malformed/unknown payloads preserve the previous live
     * composition. Once decoding succeeds, lifecycle callbacks run during commit. Their failures
     * are reported after replacement; user callback side effects cannot be rolled back.
     *
     * @param input        caller-owned full snapshot; reader index rolls back on failure
     * @param runtimeSetup client host binding before publication
     */
    public void readNetwork(FriendlyByteBuf input, Consumer<WorldObject> runtimeSetup) {
        checkThread();
        if (manager == null || object == null)
            throw new IllegalStateException("Bind the client mirror before receiving a snapshot");
        int start = input.readerIndex();
        WorldObject staged = null;
        try {
            int length = input.readInt();
            if (length < 0 || length > MAX_SNAPSHOT_BYTES - 4 || length > input.readableBytes())
                throw new WorldObjectException("Invalid hosted binary snapshot length");
            FriendlyByteBuf payload = new FriendlyByteBuf(input.readSlice(length));
            if (payload.readVarInt() != 1)
                throw new WorldObjectException("Unsupported hosted wire format");
            UUID id = payload.readUUID();
            WorldObject conflict = manager.get(id).orElse(null);
            if (conflict != null && conflict != object)
                throw new WorldObjectException("Hosted snapshot UUID collision");
            int count = payload.readVarInt();
            if (count < 0 || count > 4096)
                throw new WorldObjectException("Invalid hosted component count");
            var entries = new ArrayList<WorldObject.ComponentEntry>(count);
            var sequences = new HashSet<Long>();
            var counts = new HashMap<String, Integer>();
            var instances = new IdentityHashMap<WorldObjectComponent, Boolean>();
            for (int i = 0; i < count; i++) {
                String typeId = payload.readUtf(1024);
                var type =
                        WorldObjectComponentRegistry.instance()
                                .get(typeId)
                                .orElseThrow(
                                        () ->
                                                new WorldObjectException(
                                                        "Unknown hosted component " + typeId));
                long sequence = payload.readVarLong();
                if (sequence < 0 || sequence == Long.MAX_VALUE || !sequences.add(sequence))
                    throw new WorldObjectException("Invalid hosted attachment sequence");
                if (counts.merge(typeId, 1, Integer::sum) > 1
                        && type.duplicatePolicy()
                        != WorldObjectComponentType.DuplicatePolicy.ALLOW_MULTIPLE)
                    throw new WorldObjectException("Duplicate hosted component " + typeId);
                WorldObjectComponent component = type.createDefault();
                if (!(component instanceof AbstractWorldObjectComponent)
                        || !type.componentClass().isInstance(component)
                        || component.isAttached()
                        || instances.put(component, Boolean.TRUE) != null)
                    throw new WorldObjectException(
                            "Hosted factory must return a fresh detached component");
                int mode = payload.readUnsignedByte();
                if (mode == 1 && component instanceof AnnotatedWorldObjectComponent annotated)
                    annotated.readNetwork(payload);
                else if (mode == 2 && component instanceof SyncedWorldObjectComponent tracked)
                    tracked.readFull(payload);
                else if (mode != 0
                        || component instanceof AnnotatedWorldObjectComponent
                        || component instanceof SyncedWorldObjectComponent)
                    throw new WorldObjectException("Hosted component wire mode mismatch");
                entries.add(new WorldObject.ComponentEntry(component, type, sequence));
            }
            if (payload.isReadable())
                throw new WorldObjectException("Trailing hosted snapshot bytes");
            staged = new WorldObject(id);
            staged.markHosted();
            staged.prepareRestoredComponents(entries);
            runtimeSetup.accept(staged);
        } catch (Throwable failure) {
            input.readerIndex(start);
            EcsFailures.rethrowFatal(failure);
            if (staged != null && !staged.isDetached()) {
                try {
                    staged.detach();
                } catch (Throwable cleanup) {
                    EcsFailures.rethrowFatal(cleanup);
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        WorldObject previous = object;
        WorldObjectManager owner = manager;
        WorldObjectException lifecycleFailure =
                new WorldObjectException("Hosted snapshot lifecycle failed");
        try {
            owner.remove(previous.id());
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            lifecycleFailure.addSuppressed(failure);
        }
        object = null;
        manager = null;
        archive = WorldObjectArchive.empty();
        saved = null;
        restoreFailure = null;
        try {
            if (!owner.add(staged))
                throw new WorldObjectException("Cannot install hosted snapshot");
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            lifecycleFailure.addSuppressed(failure);
        }
        if (staged.manager().orElse(null) == owner) {
            object = staged;
            manager = owner;
            try {
                staged.finishRestoredComponents();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                lifecycleFailure.addSuppressed(failure);
            }
        } else if (!staged.isDetached()) {
            try {
                staged.detach();
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                lifecycleFailure.addSuppressed(failure);
            }
        }
        if (lifecycleFailure.getSuppressed().length != 0) throw lifecycleFailure;
    }

    /**
     * Creates an empty handle without allocating an ECS object or invoking user callbacks.
     */
    public HostedWorldObject() {
    }

    /**
     * Returns the active object without creating one. Inspect component values on the manager
     * thread.
     *
     * @return active live object, or empty when unloaded or quarantined
     */
    public Optional<WorldObject> object() {
        WorldObject current = object;
        return current == null || current.isDetached() ? Optional.empty() : Optional.of(current);
    }

    /**
     * @return last restore diagnostic, or empty after successful binding
     */
    public Optional<String> restoreFailure() {
        return Optional.ofNullable(restoreFailure);
    }

    /**
     * Copies host-owned disk data before binding. Unknown/future formats remain opaque and are
     * saved unchanged.
     *
     * @param input single-object archive, or an empty compound for a new host
     * @throws IllegalStateException if already bound or called from a different thread
     */
    public void load(CompoundTag input) {
        checkThread();
        if (object != null)
            throw new IllegalStateException("Unbind the hosted object before loading disk data");
        saved = Objects.requireNonNull(input, "input").isEmpty() ? null : input.copy();
        archive = WorldObjectArchive.empty();
        restoreFailure = null;
    }

    /**
     * Registers one object in the world's normal ECS scheduler, never a separate BlockEntity
     * ticker. Defaults run only for a new host. Runtime setup runs for both new and restored
     * objects before publication. An unresolved saved object stays inactive and its original data
     * remains available through save().
     *
     * @param owner        owning world manager
     * @param defaults     initial typed components for a new host, including stable persistence
     *                     registrations
     * @param runtimeSetup transient position/host binding and activation setup; must not publish to
     *                     the manager
     * @return true when active, false when saved data cannot currently be restored
     */
    public boolean bind(
            WorldObjectManager owner,
            Consumer<WorldObject> defaults,
            Consumer<WorldObject> runtimeSetup) {
        checkThread();
        owner.checkThread();
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(runtimeSetup, "runtimeSetup");
        if (object != null) {
            if (manager != owner || object.isDetached())
                throw new IllegalStateException("Hosted object has another or closed owner");
            return true;
        }
        if (owner.isClosed()) throw new IllegalStateException("Cannot bind to a closed manager");
        WorldObject staged;
        WorldObjectArchive.Restored restored = null;
        if (saved == null) {
            staged = new WorldObject();
        } else {
            try {
                if (!(saved.get("objects") instanceof ListTag records) || records.size() != 1)
                    throw new WorldObjectPersistenceException(
                            "Hosted archive must contain exactly one object");
                var decoded = WorldObjectArchive.decode(saved);
                if (!decoded.warnings().isEmpty() || decoded.objects().size() != 1)
                    throw new WorldObjectPersistenceException(
                            String.join("; ", decoded.warnings()));
                archive = decoded.archive();
                restored = decoded.objects().get(0);
                staged = restored.object();
            } catch (WorldObjectPersistenceException failure) {
                restoreFailure = failure.getMessage();
                return false;
            }
        }
        try {
            staged.markHosted();
            if (saved == null) defaults.accept(staged);
            runtimeSetup.accept(staged);
            if (!owner.add(staged))
                throw new IllegalStateException(
                        "Hosted ECS UUID is already registered: " + staged.id());
            object = staged;
            manager = owner;
            if (restored != null) {
                staged.restoreSchedule(
                        owner.lastTick(),
                        restored.interval(),
                        restored.delay(),
                        restored.sleeping());
                owner.refreshActiveObjects();
                staged.finishRestoredComponents();
            }
            restoreFailure = null;
            return true;
        } catch (Throwable failure) {
            EcsFailures.rethrowFatal(failure);
            if (staged.manager().orElse(null) == owner) {
                object = staged;
                manager = owner;
            } else if (!staged.isDetached()) {
                try {
                    staged.detach();
                } catch (Throwable cleanup) {
                    EcsFailures.rethrowFatal(cleanup);
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /**
     * Creates an independent host snapshot. Unresolved data is returned unchanged, never replaced
     * by defaults.
     *
     * @return complete single-object archive, or an empty compound before the first activation
     */
    public CompoundTag save() {
        checkThread();
        if (object == null) return saved == null ? new CompoundTag() : saved.copy();
        manager.checkThread();
        if (object.isDetached() || manager.isClosed())
            throw new IllegalStateException(
                    "Hosted object was removed without host lifecycle persistence");
        saved = archive.encodeHosted(object, manager.lastTick());
        return saved.copy();
    }

    /**
     * Captures before removal, then detaches exact membership and invalidates queued component
     * work. Rebinding recreates components from this snapshot and preserves the object UUID.
     */
    public void unbind() {
        checkThread();
        if (object == null) return;
        if (!object.isDetached() && !manager.isClosed()) {
            save();
            try {
                manager.remove(object.id());
            } finally {
                object = null;
                manager = null;
            }
            return;
        }
        object = null;
        manager = null;
    }

    /**
     * Rejects lifecycle access from workers, including synchronous compute fallback.
     */
    private void checkThread() {
        if (Thread.currentThread() != ownerThread)
            throw new IllegalStateException("Hosted object belongs to its world thread");
        if (manager != null) manager.checkThread();
    }
}
