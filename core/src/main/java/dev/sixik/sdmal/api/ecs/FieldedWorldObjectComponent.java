package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.sync.Field;
import dev.sixik.sdmal.api.ecs.sync.FieldCodec;
import dev.sixik.sdmal.api.ecs.sync.TrackedField;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base with declarative {@link Field}s and automatic NBT persistence.
 *
 * <p>Поля объявляются в конструкторе через {@link #field} / {@link #tracked} и после этого
 * сохраняются/загружаются сами: {@link #writeNbt}/{@link #readNbt} обходят реестр по именам,
 * отсутствующие ключи оставляют текущие значения. Ручной NBT нужен только для данных вне полей —
 * через {@link #writeExtraNbt}/{@link #readExtraNbt}.
 *
 * <p>Порядок объявления полей стабилен и доступен через {@link #syncedFields}; наследники
 * используют его как основу сетевой схемы (см. {@code SyncedWorldObjectComponent}).
 */
public abstract class FieldedWorldObjectComponent extends AbstractWorldObjectComponent
        implements PersistentWorldObjectComponent {

    /**
     * Declared fields in stable declaration order; frozen before serialization.
     */
    private final List<Field<?>> fields = new ArrayList<>();

    /**
     * Field declarations indexed by their unique persistence names.
     */
    private final Map<String, Field<?>> byName = new LinkedHashMap<>();

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
     * @param <Value> field or callback value type
     * @param name stable field name; must not be blank
     * @param codec non-null codec defining persistence, copy and equality semantics
     * @param defaultValue fallback for missing data, possibly null
     * @return registered field instance, never null
     * @throws IllegalStateException if the schema is frozen or this component is attached
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
     * @param <Value> field or callback value type
     * @param name stable field name; must not be blank
     * @param codec non-null codec defining persistence, copy and equality semantics
     * @param defaultValue fallback for missing data, possibly null
     * @return registered field instance, never null
     * @throws IllegalStateException if the schema is frozen or this component is attached
     * @throws IllegalArgumentException if the field name is blank or already declared
     */
    protected final <Value> TrackedField<Value> tracked(
            String name, FieldCodec<Value> codec, Value defaultValue) {
        return declare(new TrackedField<>(name, codec, defaultValue));
    }

    /**
     * Registers a field while the component schema is still mutable.
     *
     * @param <F> field or callback value type
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
                fieldSchema = List.copyOf(fields);
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
    public void writeNbt(CompoundTag nbt) {
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
    public void readNbt(CompoundTag nbt) {
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
    protected void writeExtraNbt(CompoundTag nbt) {}

    /**
     * Hook for state kept outside {@link Field}s. Called after fields in {@link #readNbt}. Default:
     * nothing.
     *
     * @param nbt source or destination NBT compound, non-null
     */
    protected void readExtraNbt(CompoundTag nbt) {}

    /**
     * Encodes a declared field under its stable persistence name.
     *
     * @param nbt source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static void writeNbtValue(CompoundTag nbt, Field<?> tracked) {
        writeNbtValueCaptured(nbt, tracked);
    }

    /**
     * Encodes a declared field under its stable persistence name.
     *
     * @param <Value> field or callback value type
     * @param nbt source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static <Value> void writeNbtValueCaptured(CompoundTag nbt, Field<Value> tracked) {
        tracked.codec().toNbt(nbt, tracked.name(), tracked.get());
    }

    /**
     * Decodes a declared field while preserving its current value for missing keys.
     *
     * @param nbt source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static void readNbtValue(CompoundTag nbt, Field<?> tracked) {
        readNbtValueCaptured(nbt, tracked);
    }

    /**
     * Decodes a declared field while preserving its current value for missing keys.
     *
     * @param <Value> field or callback value type
     * @param nbt source or destination NBT compound, non-null
     * @param tracked declared field whose codec owns the value type
     */
    private static <Value> void readNbtValueCaptured(CompoundTag nbt, Field<Value> tracked) {
        tracked.set(tracked.codec().fromNbt(nbt, tracked.name(), tracked.get()));
    }
}
