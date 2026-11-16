package dev.sixik.sdmal.core.ecs.sync;

import java.util.Objects;
import java.util.concurrent.locks.StampedLock;
import java.util.function.UnaryOperator;

/**
 * A {@link Field} guarded by a {@link StampedLock}, with a monotonic revision.
 *
 * <p>Поле для многопоточных компонентов ({@code SyncedWorldObjectComponent}):
 * optimistic reads для {@link #get}, эксклюзивные writes для {@link #set}.
 * Каждая запись сравнивает новое значение с живым под write-stamp, поэтому
 * гонящиеся писатели сериализуются и ни одно обновление не теряется. Ревизия
 * растёт только на реальном изменении (по {@link FieldCodec#areEqual});
 * установка равного значения ревизию не трогает — это и держит сетевые
 * дельты тихими.
 *
 * @param <Value> field value type
 */
public final class TrackedField<Value> extends Field<Value> {

    /**
     * Stamped lock pairing field values with monotonically increasing revisions.
     */
    private final StampedLock lock = new StampedLock();

    /**
     * Current field revision, initially zero and incremented on effective changes.
     */
    private volatile long revision;

    /**
     * Creates a field with the given default. The default is copied once through
     * {@link FieldCodec#copy} so later external mutation of the argument cannot
     * leak into the field.
     * @param name stable field name; must not be blank
     * @param codec non-null codec defining persistence, copy and equality semantics
     * @param defaultValue fallback for missing data, possibly null
     */
    public TrackedField(String name, FieldCodec<Value> codec, Value defaultValue) {
        super(name, codec, defaultValue);
        this.revision = 0L;
    }

    /**
     * Returns the live value (no copy). Optimistic read, never blocks writers.
     * @return current live value without copying; null if allowed by the codec
     */
    @Override
    public Value get() {
        long stamp = lock.tryOptimisticRead();
        Value current = super.get();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                current = super.get();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return current;
    }

    /**
     * Returns the current revision. Increments on every effective change.
     * @return current sequence value, initially zero
     */
    public long revision() {
        return revision;
    }

    /**
     * Captures a value and its matching revision under one read lock.
     * @return copied value and revision pair, never null; the value itself may be null
     */
    public Snapshot<Value> snapshot() {
        long stamp = lock.readLock();
        try {
            return new Snapshot<>(codec().copy(super.get()), revision);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Immutable capture of one field version.
     * @param value defensively copied field value, possibly null
     * @param revision field revision at capture, greater than or equal to zero
     * @param <Value> field value type
     */
    public record Snapshot<Value>(Value value, long revision) {}

    /**
     * Installs {@code newValue}. Returns {@code true} when the value changed.
     * The comparison runs under the write stamp against the live value, so racing
     * writers serialize and no update is lost: each writer compares its own
     * argument to whatever is current at its turn.
     * @param newValue replacement value; copied through the field codec
     * @return true only when codec equality detects a value change
     */
    @Override
    public boolean set(Value newValue) {
        long stamp = lock.writeLock();
        try {
            long before = revision;
            boolean changed = super.set(newValue);
            if (changed) {
                revision = before + 1;
            }
            return changed;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Atomically replaces the value with {@code remap.apply(live)}. The function
     * runs under the write stamp against the live value, so read-modify-write
     * sequences (counters, appends) cannot interleave. Returns {@code true} on change.
     * @param remap non-null transformation applied to a defensive copy of the current value
     * @return true only when codec equality detects a value change
     */
    @Override
    public boolean update(UnaryOperator<Value> remap) {
        Objects.requireNonNull(remap, "remap");
        long stamp = lock.writeLock();
        try {
            long before = revision;
            boolean changed = super.update(remap);
            if (changed) {
                revision = before + 1;
            }
            return changed;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Copies the value if its revision is newer than {@code knownRevision}.
     * Returns {@code null} when nothing changed, which lets callers build deltas
     * without allocating per-field snapshots every tick.
     * @param knownRevision last acknowledged revision; -1 means unknown
     * @return copied newer value, or null if unchanged; null-valued fields are also possible
     */
    public Value copyIfNewerThan(long knownRevision) {
        long stamp = lock.readLock();
        try {
            if (revision <= knownRevision) {
                return null;
            }
            return codec().copy(super.get());
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Compares the live value against {@code snapshot} with codec equality.
     * @param snapshot previously captured value to compare against
     * @return true if codec equality considers both values equal
     */
    @Override
    public boolean isEqualTo(Value snapshot) {
        long stamp = lock.tryOptimisticRead();
        Value current = super.get();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                current = super.get();
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return codec().areEqual(current, snapshot);
    }
}
