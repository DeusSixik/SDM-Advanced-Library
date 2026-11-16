package dev.sixik.sdmal.core.ecs.sync;

import dev.sixik.sdmal.core.ecs.FieldedWorldObjectComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Component base with declarative {@link TrackedField}s, full-state NBT persistence
 * and revision-based network deltas.
 *
 * <p>Usage: declare fields via {@link #tracked} (inherited from
 * {@code FieldedWorldObjectComponent}), then mutate through the returned handles.
 * Only {@link TrackedField} instances participate in the network schema; a plain
 * {@link Field} declared via {@code field()} is NBT-only and never touches the wire.
 * Field order is declaration order and is part of the network schema:
 * {@link #writeFull} writes values in that order, {@link #readFull} reads them back
 * positionally. NBT persistence ({@link #writeNbt}/{@link #readNbt}) comes from
 * {@code FieldedWorldObjectComponent} and is key-based and tolerant: missing keys
 * keep current values.
 *
 * <p>Threading: fields are individually guarded by StampedLock,
 * so concurrent writes to different fields never contend. Delta collection takes
 * per-field read stamps one at a time; a field that changes mid-collection lands
 * in the next delta, never torn within one field. {@link #writeDelta}/{@link
 * #readDelta} carry {@code (fieldIndex, revision)} pairs, so a newer full sync
 * cannot be overwritten by a stale delta on arrival.
 *
 * <p>Reader protocol: each side keeps the last applied revision per field index
 * (start at {@code -1}). After {@link #readFull}, reset all trackers to the
 * sender's revisions. After {@link #readDelta}, apply only entries with
 * {@code revision > known}, then store the sender's revision per touched field.
 * {@link DeltaTracker} implements exactly this bookkeeping.
 *
 * <p>Network schema: {@link #schemaFingerprint} hashes
 * each field name and codec schema name, separated by {@code 0xFF}. Compare fingerprints before
 * applying a delta; on mismatch request a full sync or drop the packet instead of
 * decoding garbage. NBT persistence ({@link #writeNbt}/{@link #readNbt}) is
 * key-based and tolerant: missing keys keep current values.
 */
public abstract class SyncedWorldObjectComponent extends FieldedWorldObjectComponent {

    /** Immutable tracked-field schema, published after the first network operation. */
    private volatile List<TrackedField<?>> networkSchema;
    /** Fingerprint computed before networkSchema is published. */
    private long fingerprint;

    /**
     * Returns only tracked fields in declaration order; plain fields remain NBT-only.
     * @return dense network field sequence, never null
     */
    private List<TrackedField<?>> networkFields() {
        List<TrackedField<?>> cached = networkSchema;
        if (cached != null) return cached;
        synchronized (this) {
            if (networkSchema == null) {
                List<TrackedField<?>> networked = new ArrayList<>();
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
                networkSchema = List.copyOf(networked);
            }
            return networkSchema;
        }
    }

    /**
     * Stable fingerprint of the network schema. Exchange once per session (or with
     * every delta in dev builds) and refuse deltas on mismatch.
     * @return 64-bit schema hash; compare for exact equality before exchanging deltas
     */
    public final long schemaFingerprint() {
        networkFields();
        return fingerprint;
    }

    /**
     * Writes every field value in declaration order (no field names on the wire).
     * @param buf network buffer; reader/writer indices advance on success
     */
    public final void writeFull(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        List<TrackedField.Snapshot<?>> snapshots = new ArrayList<>(fields.size());
        for (TrackedField<?> field : fields) snapshots.add(field.snapshot());
        buf.writeVarInt(fields.size());
        for (var snapshot : snapshots) buf.writeVarLong(snapshot.revision());
        for (int index = 0; index < fields.size(); index++) {
            writeSnapshot(buf, fields.get(index), snapshots.get(index));
        }
    }

    /**
     * Reads a full state written by {@link #writeFull}. Unknown trailing data is
     * left for the caller; missing fields are impossible by construction (full
     * always carries all), so any short read throws from the buffer itself.
     * @param buf network buffer; reader/writer indices advance on success
     */
    public final void readFull(FriendlyByteBuf buf) {
        readFullWithRevisions(buf);
    }

    /**
     * Reads a full state and returns the sender revisions per field index.
     * Feed them into {@link DeltaTracker#reset} on the receiving side.
     * @param buf network buffer; reader/writer indices advance on success
     * @return sender revisions in network field declaration order
     */
    public final long[] readFullWithRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        long[] revisions = readRevisions(buf);
        List<Object> values = new ArrayList<>(fields.size());
        for (TrackedField<?> field : fields) values.add(field.codec().fromNetwork(buf));
        for (int index = 0; index < fields.size(); index++) applyValue(fields.get(index), values.get(index));
        return revisions;
    }

    /**
     * Writes only fields whose revision is newer than the tracker's snapshot.
     * Format: {@code varint count}, then per entry {@code varint index},
     * {@code varlong revision}, value. Returns {@code true} when at least one
     * field was written. Trackers advance only to the exact revisions encoded,
     * after all values have been written successfully. Concurrent changes remain pending.
     * @param buf network buffer; reader/writer indices advance on success
     * @param tracker per-viewer revision bookkeeping, confined to the calling thread
     * @return true if at least one changed field was encoded; false leaves the buffer untouched
     */
    public final boolean writeDelta(FriendlyByteBuf buf, DeltaTracker tracker) {
        Objects.requireNonNull(tracker, "tracker");
        List<TrackedField<?>> fields = networkFields();
        tracker.ensureCapacity(fields.size());
        List<Integer> touched = new ArrayList<>();
        List<TrackedField.Snapshot<?>> snapshots = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            TrackedField<?> tracked = fields.get(i);
            if (tracked.revision() > tracker.known(i)) {
                touched.add(i);
                snapshots.add(tracked.snapshot());
            }
        }
        if (touched.isEmpty()) return false;
        buf.writeVarInt(touched.size());
        for (int entry = 0; entry < touched.size(); entry++) {
            int index = touched.get(entry);
            var snapshot = snapshots.get(entry);
            buf.writeVarInt(index);
            buf.writeVarLong(snapshot.revision());
            writeSnapshot(buf, fields.get(index), snapshot);
        }
        for (int entry = 0; entry < touched.size(); entry++) {
            tracker.mark(touched.get(entry), snapshots.get(entry).revision());
        }
        return true;
    }

    /**
     * Applies a delta written by {@link #writeDelta}. Entries with
     * {@code revision <= tracker.known(index)} are skipped but still consumed
     * from the buffer, so out-of-order redelivery cannot roll state back.
     * Unknown indexes throw {@link SyncException}.
     * @param buf network buffer; reader/writer indices advance on success
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
        boolean[] seen = new boolean[fields.size()];
        int[] indexes = new int[count];
        long[] revisions = new long[count];
        List<Object> values = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            int index = buf.readVarInt();
            long revision = buf.readVarLong();
            if (index < 0 || index >= fields.size()) {
                throw new SyncException("Delta references unknown field index " + index
                        + " in " + getClass().getName());
            }
            if (revision < 0 || seen[index]) throw new SyncException("Invalid or duplicate delta field " + index);
            seen[index] = true;
            indexes[n] = index;
            revisions[n] = revision;
            TrackedField<?> tracked = fields.get(index);
            values.add(tracked.codec().fromNetwork(buf));
        }
        for (int n = 0; n < count; n++) {
            if (revisions[n] <= tracker.known(indexes[n])) continue;
            applyValue(fields.get(indexes[n]), values.get(n));
            tracker.mark(indexes[n], revisions[n]);
        }
    }

    // NBT persistence is inherited from FieldedWorldObjectComponent (key-based,
    // tolerant to missing keys). Network helpers below.

    /**
     * Per-viewer revision bookkeeping for {@link #writeDelta}/{@link #readDelta}.
     * Not thread-safe by itself: confine one tracker per viewer/session and touch
     * it from a single thread, or guard externally.
     */
    public static final class DeltaTracker {

        /**
         * Per-field acknowledged revisions; -1 means unknown.
         */
        private long[] known = new long[0];

        /**
         * Creates a tracker pre-sized for {@code fieldCount} fields, all unknown ({@code -1}).
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
         * @param index zero-based field index
         * @param revision non-negative sender revision
         */
        public void mark(int index, long revision) {
            if (index < 0 || revision < 0) throw new IllegalArgumentException("Negative field index or revision");
            ensureCapacity(index + 1);
            known[index] = revision;
        }

        /**
         * Resets all trackers to the sender's full-sync revisions.
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
     * @param buf network buffer; reader/writer indices advance on success
     * @return validated non-negative revisions in network field order
     */
    private long[] readRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        int size = buf.readVarInt();
        if (size != fields.size()) {
            throw new SyncException("Full sync field count mismatch: got " + size
                    + ", expected " + fields.size() + " in " + getClass().getName());
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
     * Encodes a snapshot captured from the supplied field under its read lock.
     * @param buf destination buffer, non-null
     * @param field field defining the snapshot's value codec, non-null
     * @param snapshot capture from exactly this field, non-null
     * @param <Value> value type shared by field and snapshot
     */
    @SuppressWarnings("unchecked")
    private static <Value> void writeSnapshot(FriendlyByteBuf buf, TrackedField<Value> field,
                                              TrackedField.Snapshot<?> snapshot) {
        field.codec().toNetwork(buf, (Value) snapshot.value());
    }

    /**
     * Applies a decoded value using the field's declared codec type.
     * @param field destination field, non-null
     * @param value value decoded by this field's codec, possibly null
     * @param <Value> field value type
     */
    @SuppressWarnings("unchecked")
    private static <Value> void applyValue(TrackedField<Value> field, Object value) {
        field.set((Value) value);
    }

}
