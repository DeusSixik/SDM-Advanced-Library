package dev.sixik.sdmal.api.ecs.sync;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import dev.sixik.sdmal.api.ecs.Component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Specialized Component with explicit tracked fields and per-viewer revision deltas.
 * Prefer Component with EcsPersist/EcsSync for ordinary data. Choose this base only when field
 * handles, full/delta snapshots and acknowledgement tracking are required. Declare tracked()
 * fields in the constructor; field() adds NBT-only values. NBT keys and positional tracked wire
 * formats retain their established representation. This base does not send packets automatically.
 *
 * <p>TrackedField protects individual values, not a multi-field transaction or access to Minecraft.
 * Lifecycle and persistence still follow the owning manager's thread contract. Full snapshots
 * return revisions; reset each reader's DeltaTracker after receiving one to reject stale deltas.
 */
public abstract class NetworkComponent extends Component {


    /**
     * Declared fields in stable declaration order; frozen before serialization.
     */
    private final List<Field<?>> fields = new ObjectArrayList<>();

    /**
     * Field declarations indexed by their unique persistence names.
     */
    private final Map<String, Field<?>> byName = new Object2ObjectLinkedOpenHashMap<>();

    /**
     * Prevents schema changes once fields have been exposed to serialization.
     */
    private boolean fieldsFrozen;

    /**
     * Published immutable schema; null until the first schema observation.
     */
    private volatile List<Field<?>> fieldSchema;

    /**
     * Declares a plain field (no locking, no revision). For single-threaded components. Must be
     * called from the constructor, before attach. Duplicate names are rejected.
     *
     * @param <Value>      field or callback value type
     * @param name         stable field name; must not be blank
     * @param codec        non-null codec defining persistence, copy and equality semantics
     * @param defaultValue fallback for missing data, possibly null
     * @return registered field instance, never null
     * @throws IllegalStateException    if the schema is frozen or this component is attached
     * @throws IllegalArgumentException if the field name is blank or already declared
     */
    protected final <Value> Field<Value> field(
            String name, FieldCodec<Value> codec, Value defaultValue) {
        return declare(new Field<>(name, codec, defaultValue));
    }

    /**
     * Declares a tracked field (per-field lock, monotonic revision). For synced multithreaded
     * components. Must be called from the constructor, before attach. Duplicate names are rejected.
     *
     * @param <Value>      field or callback value type
     * @param name         stable field name; must not be blank
     * @param codec        non-null codec defining persistence, copy and equality semantics
     * @param defaultValue fallback for missing data, possibly null
     * @return registered field instance, never null
     * @throws IllegalStateException    if the schema is frozen or this component is attached
     * @throws IllegalArgumentException if the field name is blank or already declared
     */
    protected final <Value> TrackedField<Value> tracked(
            String name, FieldCodec<Value> codec, Value defaultValue) {
        return declare(new TrackedField<>(name, codec, defaultValue));
    }

    /**
     * Registers a field while the component schema is still mutable.
     *
     * @param <F>   field or callback value type
     * @param field new field declaration; names must be unique within the component
     * @return registered field instance, never null
     */
    private synchronized <F extends Field<?>> F declare(F field) {
        if (fieldsFrozen || isAttached())
            throw new IllegalStateException("Declare fields only in the constructor");
        if (byName.putIfAbsent(field.name(), field) != null) {
            throw new IllegalArgumentException(
                    "Duplicate field '" + field.name() + "' in " + getClass().getName());
        }
        fields.add(field);
        return field;
    }

    /**
     * Declared fields in declaration order. The immutable schema is cached on first access.
     *
     * @return immutable list of all declared fields in declaration order
     */
    public final List<Field<?>> syncedFields() {
        List<Field<?>> cached = fieldSchema;
        if (cached != null) return cached;
        synchronized (this) {
            if (fieldSchema == null) {
                fieldsFrozen = true;
                fieldSchema = ObjectArrayView.copyOf(fields).readOnly();
            }
            return fieldSchema;
        }
    }

    /**
     * Looks up a field by name.
     *
     * @param name stable field name; must not be blank
     * @return field registered under this name, never null
     * @throws IllegalArgumentException if no field has this name
     */
    public final Field<?> fieldByName(String name) {
        Field<?> tracked = byName.get(name);
        if (tracked == null) {
            throw new IllegalArgumentException(
                    "Unknown field '" + name + "' in " + getClass().getName());
        }
        return tracked;
    }

    /**
     * Writes all fields into {@code nbt} under their names, then {@link #writeExtraNbt}.
     *
     * @param nbt source or destination NBT compound, non-null
     */
    @Override
    public void writeNbt(CompoundTag nbt) {
        checkOwnerThread();
        Objects.requireNonNull(nbt, "nbt");
        for (Field<?> tracked : syncedFields()) {
            writeNbtValue(nbt, tracked);
        }
        writeExtraNbt(nbt);
    }

    /**
     * Reads fields from {@code nbt} by name, then {@link #readExtraNbt}. Missing keys keep current
     * values, which makes saves forward-compatible with newly added fields.
     *
     * @param nbt source or destination NBT compound, non-null
     */
    @Override
    public void readNbt(CompoundTag nbt) {
        checkOwnerThread();
        Objects.requireNonNull(nbt, "nbt");
        for (Field<?> tracked : syncedFields()) {
            readNbtValue(nbt, tracked);
        }
        readExtraNbt(nbt);
    }

    /**
     * Hook for state kept outside {@link Field}s. Called after fields in {@link #writeNbt}.
     * Default: nothing.
     *
     * @param nbt source or destination NBT compound, non-null
     */
    protected void writeExtraNbt(CompoundTag nbt) {
    }

    /**
     * Hook for state kept outside {@link Field}s. Called after fields in {@link #readNbt}. Default:
     * nothing.
     *
     * @param nbt source or destination NBT compound, non-null
     */
    protected void readExtraNbt(CompoundTag nbt) {
    }

    /**
     * Encodes a declared field under its stable persistence name.
     *
     * @param nbt     source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static void writeNbtValue(CompoundTag nbt, Field<?> tracked) {
        writeNbtValueCaptured(nbt, tracked);
    }

    /**
     * Encodes a declared field under its stable persistence name.
     *
     * @param <Value> field or callback value type
     * @param nbt     source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static <Value> void writeNbtValueCaptured(CompoundTag nbt, Field<Value> tracked) {
        tracked.codec().toNbt(nbt, tracked.name(), tracked.get());
    }

    /**
     * Decodes a declared field while preserving its current value for missing keys.
     *
     * @param nbt     source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static void readNbtValue(CompoundTag nbt, Field<?> tracked) {
        readNbtValueCaptured(nbt, tracked);
    }

    /**
     * Decodes a declared field while preserving its current value for missing keys.
     *
     * @param <Value> field or callback value type
     * @param nbt     source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static <Value> void readNbtValueCaptured(CompoundTag nbt, Field<Value> tracked) {
        tracked.set(tracked.codec().fromNbt(nbt, tracked.name(), tracked.get()));
    }


    /**
     * Immutable tracked-field schema, published after the first network operation.
     */
    private volatile List<TrackedField<?>> networkSchema;

    /**
     * Fingerprint computed before networkSchema is published.
     */
    private long fingerprint;

    /**
     * Returns only tracked fields in declaration order; plain fields remain NBT-only.
     *
     * @return dense network field sequence, never null
     */
    private List<TrackedField<?>> networkFields() {
        List<TrackedField<?>> cached = networkSchema;
        if (cached != null) return cached;
        synchronized (this) {
            if (networkSchema == null) {
                List<TrackedField<?>> networked = new ObjectArrayList<>();
                for (Field<?> field : syncedFields()) {
                    if (field instanceof TrackedField<?> tracked) networked.add(tracked);
                }
                long hash = 0x9E3779B97F4A7C15L;
                for (TrackedField<?> field : networked) {
                    hash = mix(hash, field.name());
                    hash = mix(hash, field.codec().schemaName());
                    hash = hash * 0x100000001B3L + networked.size();
                }
                fingerprint = hash;
                networkSchema = ObjectArrayView.copyOf(networked);
            }
            return networkSchema;
        }
    }

    /**
     * Stable fingerprint of the network schema. Exchange once per session (or with every delta in
     * dev builds) and refuse deltas on mismatch.
     *
     * @return 64-bit schema hash; compare for exact equality before exchanging deltas
     */
    public final long schemaFingerprint() {
        networkFields();
        return fingerprint;
    }

    /**
     * Writes every field value in declaration order (no field names on the wire).
     *
     * @param buf network buffer; reader/writer indices advance on success
     */
    public final void writeFull(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        List<TrackedField.Snapshot<?>> snapshots = new ObjectArrayList<>(fields.size());
        for (TrackedField<?> field : fields) snapshots.add(field.snapshot());
        buf.writeVarInt(fields.size());
        for (var snapshot : snapshots) buf.writeVarLong(snapshot.revision());
        for (int index = 0; index < fields.size(); index++) {
            writeValue(buf, fields.get(index), snapshots.get(index).value());
        }
    }

    /**
     * Reads a full state written by {@link #writeFull}. Unknown trailing data is left for the
     * caller; missing fields are impossible by construction (full always carries all), so any short
     * read throws from the buffer itself.
     *
     * @param buf network buffer; reader/writer indices advance on success
     */
    public final void readFull(FriendlyByteBuf buf) {
        readFullWithRevisions(buf);
    }

    /**
     * Reads a full state and returns the sender revisions per field index. Feed them into {@link
     * DeltaTracker#reset} on the receiving side.
     *
     * @param buf network buffer; reader/writer indices advance on success
     * @return sender revisions in network field declaration order
     */
    public final long[] readFullWithRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        long[] revisions = readRevisions(buf);
        List<Object> values = new ObjectArrayList<>(fields.size());
        for (TrackedField<?> field : fields) values.add(field.codec().fromNetwork(buf));
        for (int index = 0; index < fields.size(); index++)
            applyValue(fields.get(index), values.get(index));
        return revisions;
    }

    /**
     * Writes only fields whose revision is newer than the tracker's snapshot. Format: {@code varint
     * count}, then per entry {@code varint index}, {@code varlong revision}, value. Returns {@code
     * true} when at least one field was written. Trackers advance only to the exact revisions
     * encoded, after all values have been written successfully. Concurrent changes remain pending.
     *
     * @param buf     network buffer; reader/writer indices advance on success
     * @param tracker per-viewer revision bookkeeping, confined to the calling thread
     * @return true if at least one changed field was encoded; false leaves the buffer untouched
     */
    public final boolean writeDelta(FriendlyByteBuf buf, DeltaTracker tracker) {
        Objects.requireNonNull(tracker, "tracker");
        List<TrackedField<?>> fields = networkFields();
        tracker.ensureCapacity(fields.size());
        DeltaBuffer staging = null;
        int changed = 0;
        try {
            for (int index = 0; index < fields.size(); index++) {
                TrackedField<?> field = fields.get(index);
                if (field.revision() <= tracker.known(index)) continue;
                if (staging == null) staging = tracker.acquire(fields.size());
                staging.indexes[changed] = index;
                staging.used = changed + 1;
                staging.revisions[changed] = field.captureInto(staging.values, changed);
                changed++;
            }
            if (changed == 0) return false;
            buf.writeVarInt(changed);
            for (int entry = 0; entry < changed; entry++) {
                int index = staging.indexes[entry];
                buf.writeVarInt(index);
                buf.writeVarLong(staging.revisions[entry]);
                writeValue(buf, fields.get(index), staging.values[entry]);
            }
            for (int entry = 0; entry < changed; entry++) {
                tracker.mark(staging.indexes[entry], staging.revisions[entry]);
            }
            return true;
        } finally {
            if (staging != null) tracker.release(staging);
        }
    }

    /**
     * Applies a delta written by {@link #writeDelta}. Entries with {@code revision <=
     * tracker.known(index)} are skipped but still consumed from the buffer, so out-of-order
     * redelivery cannot roll state back. Unknown indexes throw {@link SyncException}.
     *
     * @param buf     network buffer; reader/writer indices advance on success
     * @param tracker per-viewer revision bookkeeping, confined to the calling thread
     */
    public final void readDelta(FriendlyByteBuf buf, DeltaTracker tracker) {
        Objects.requireNonNull(tracker, "tracker");
        List<TrackedField<?>> fields = networkFields();
        tracker.ensureCapacity(fields.size());
        int count = buf.readVarInt();
        if (count < 0 || count > fields.size()) {
            throw new SyncException("Delta entry count out of bounds: " + count);
        }
        if (count == 0) return;
        DeltaBuffer staging = tracker.acquire(fields.size());
        try {
            for (int entry = 0; entry < count; entry++) {
                int index = buf.readVarInt();
                long revision = buf.readVarLong();
                if (index < 0 || index >= fields.size()) {
                    throw new SyncException(
                            "Delta references unknown field index "
                                    + index
                                    + " in "
                                    + getClass().getName());
                }
                if (revision < 0 || staging.seen[index]) {
                    throw new SyncException("Invalid or duplicate delta field " + index);
                }
                staging.seen[index] = true;
                staging.indexes[entry] = index;
                staging.seenCount = staging.used = entry + 1;
                staging.revisions[entry] = revision;
                staging.values[entry] = fields.get(index).codec().fromNetwork(buf);
            }
            for (int entry = 0; entry < count; entry++) {
                int index = staging.indexes[entry];
                long revision = staging.revisions[entry];
                if (revision <= tracker.known(index)) continue;
                applyValue(fields.get(index), staging.values[entry]);
                tracker.mark(index, revision);
            }
        } finally {
            tracker.release(staging);
        }
    }

    // NBT persistence is inherited from NetworkComponent (key-based,
    // tolerant to missing keys). Network helpers below.

    /**
     * Per-viewer revision bookkeeping for {@link #writeDelta}/{@link #readDelta}. Not thread-safe
     * by itself: confine one tracker per viewer/session and touch it from a single thread, or guard
     * externally.
     */
    public static final class DeltaTracker {

        /**
         * Per-field acknowledged revisions; -1 means unknown.
         */
        private long[] known = new long[0];

        /**
         * Reusable staging buffer, leased exclusively during a packet operation; null while in use.
         */
        private DeltaBuffer available;

        /**
         * Leases staging without sharing it with recursive codec calls using the same tracker.
         *
         * @param fieldCount current immutable schema size
         * @return exclusively owned staging, cleared before reuse
         */
        private DeltaBuffer acquire(int fieldCount) {
            DeltaBuffer buffer = available;
            available = null;
            if (buffer == null || buffer.values.length < fieldCount)
                buffer = new DeltaBuffer(fieldCount);
            return buffer;
        }

        /**
         * Clears user references even on codec failure, retaining at most one reusable buffer per
         * viewer.
         *
         * @param buffer exclusively leased packet staging
         */
        private void release(DeltaBuffer buffer) {
            Arrays.fill(buffer.values, 0, buffer.used, null);
            if (buffer.seenCount != 0) Arrays.fill(buffer.seen, false);
            buffer.used = buffer.seenCount = 0;
            if (available == null) available = buffer;
        }

        /**
         * Creates a tracker pre-sized for {@code fieldCount} fields, all unknown ({@code -1}).
         *
         * @param fieldCount number of network fields, greater than or equal to zero
         */
        public DeltaTracker(int fieldCount) {
            ensureCapacity(fieldCount);
        }

        /**
         * Creates an empty tracker; grows on first use.
         */
        public DeltaTracker() {
        }

        /**
         * Grows capacity to at least {@code fieldCount}, filling new slots with {@code -1}.
         *
         * @param fieldCount number of network fields, greater than or equal to zero
         */
        public void ensureCapacity(int fieldCount) {
            if (fieldCount < 0) {
                throw new IllegalArgumentException("fieldCount must be >= 0");
            }
            if (known.length >= fieldCount) {
                return;
            }
            long[] grown = new long[fieldCount];
            System.arraycopy(known, 0, grown, 0, known.length);
            for (int i = known.length; i < grown.length; i++) {
                grown[i] = -1L;
            }
            known = grown;
        }

        /**
         * Last applied revision for field {@code index} ({@code -1} = unknown).
         *
         * @param index zero-based field index
         * @return last acknowledged revision, or -1 for an unknown index
         */
        public long known(int index) {
            if (index < 0 || index >= known.length) {
                return -1L;
            }
            return known[index];
        }

        /**
         * Records the sender revision for field {@code index}.
         *
         * @param index    zero-based field index
         * @param revision non-negative sender revision
         */
        public void mark(int index, long revision) {
            if (index < 0 || revision < 0)
                throw new IllegalArgumentException("Negative field index or revision");
            ensureCapacity(index + 1);
            known[index] = revision;
        }

        /**
         * Resets all trackers to the sender's full-sync revisions.
         *
         * @param revisions sender revisions in network field order; copied by reset
         */
        public void reset(long[] revisions) {
            Objects.requireNonNull(revisions, "revisions");
            for (long revision : revisions) {
                if (revision < 0) throw new IllegalArgumentException("Negative full-sync revision");
            }
            known = revisions.clone();
        }
    }

    /**
     * Validates the field count and reads non-negative sender revisions.
     *
     * @param buf network buffer; reader/writer indices advance on success
     * @return validated non-negative revisions in network field order
     */
    private long[] readRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        int size = buf.readVarInt();
        if (size != fields.size()) {
            throw new SyncException(
                    "Full sync field count mismatch: got "
                            + size
                            + ", expected "
                            + fields.size()
                            + " in "
                            + getClass().getName());
        }
        long[] revisions = new long[size];
        for (int i = 0; i < size; i++) {
            revisions[i] = buf.readVarLong();
            if (revisions[i] < 0) throw new SyncException("Negative full-sync revision");
        }
        return revisions;
    }

    /**
     * Incorporates text and a 0xFF separator using XOR and multiplication modulo 2^64.
     *
     * @param hash running 64-bit schema hash with Java long overflow semantics
     * @param text field name or codec schema text being incorporated in the hash
     * @return updated signed 64-bit hash with overflow modulo 2^64
     */
    private static long mix(long hash, String text) {
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        hash ^= 0xFF;
        hash *= 0x100000001B3L;
        return hash;
    }

    /**
     * Encodes a defensively copied value captured from the supplied field.
     *
     * @param buf     destination buffer
     * @param field   field defining the value codec
     * @param value   independent staged value, possibly null
     * @param <Value> codec value type
     */
    @SuppressWarnings("unchecked")
    private static <Value> void writeValue(
            FriendlyByteBuf buf, TrackedField<Value> field, Object value) {
        field.codec().toNetwork(buf, (Value) value);
    }

    /**
     * Primitive packet staging owned by a single active tracker operation.
     */
    private static final class DeltaBuffer {
        /**
         * Number of populated value slots, including a slot whose decoder may have failed.
         */
        int used;

        /**
         * Number of field indexes marked in the duplicate guard during decoding; zero for writes.
         */
        int seenCount;

        /**
         * Field indexes in packet order.
         */
        final int[] indexes;

        /**
         * Revisions paired with copied values.
         */
        final long[] revisions;

        /**
         * Defensive value copies; references are cleared on every release.
         */
        final Object[] values;

        /**
         * Duplicate-field guards, cleared on every release.
         */
        final boolean[] seen;

        /**
         * Allocates staging once for one schema size outside steady-state packet encoding.
         *
         * @param count number of fields, zero or greater
         */
        DeltaBuffer(int count) {
            indexes = new int[count];
            revisions = new long[count];
            values = new Object[count];
            seen = new boolean[count];
        }
    }

    /**
     * Applies a decoded value using the field's declared codec type.
     *
     * @param field   destination field, non-null
     * @param value   value decoded by this field's codec, possibly null
     * @param <Value> field value type
     */
    @SuppressWarnings("unchecked")
    private static <Value> void applyValue(TrackedField<Value> field, Object value) {
        field.set((Value) value);
    }

    /**
     * @param buffer destination for the complete tracked-field snapshot
     */
    @Override
    public final void writeNetwork(FriendlyByteBuf buffer) {
        writeFull(buffer);
    }

    /**
     * @param buffer source complete tracked-field snapshot; use readFull when retaining revisions
     */
    @Override
    public final void readNetwork(FriendlyByteBuf buffer) {
        readFull(buffer);
    }

    /**
     * @return tracked-field schema fingerprint; compare before applying positional snapshots
     */
    @Override
    public final long networkSchemaFingerprint() {
        return schemaFingerprint();
    }
}
