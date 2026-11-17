package dev.sixik.sdmal.api.ecs.sync;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A single named value with codec-based copy and equality. No locking, no revision.
 *
 * <p>Базовое поле для однопоточных компонентов ({@code SingleThreadedWorldObjectComponent}): прямой
 * доступ к значению, ноль накладных расходов на синхронизацию. Равенство через {@link
 * FieldCodec#areEqual}, defensive copy через {@link FieldCodec#copy} — та же семантика снапшотов,
 * что и у {@link TrackedField}, но без ревизий и дельт.
 *
 * <p>Значения должны быть эффективно иммутабельными для читателей: {@link #get} возвращает живую
 * ссылку без копирования. Для мутабельных типов используйте {@link #update} с copy-on-write
 * функцией.
 *
 * @param <Value> field value type
 */
public class Field<Value> {

    /**
     * Stable field persistence key, never blank.
     */
    private final String name;

    /**
     * Codec defining serialization, equality and defensive copying, never null.
     */
    private final FieldCodec<Value> codec;

    /**
     * Current field value; mutable references must follow the codec's copy contract.
     */
    private Value value;

    /**
     * Creates a field with the given default. The default is copied once through {@link
     * FieldCodec#copy} so later external mutation of the argument cannot leak into the field.
     *
     * @param name stable field name; must not be blank
     * @param codec non-null codec defining persistence, copy and equality semantics
     * @param defaultValue fallback for missing data, possibly null
     */
    public Field(String name, FieldCodec<Value> codec, Value defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.value = codec.copy(defaultValue);
    }

    /**
     * Field name, stable across restarts. Used as the NBT key.
     *
     * @return diagnostic or stable identifier text, never null
     */
    public final String name() {
        return name;
    }

    /**
     * Codec used for NBT, network and equality.
     *
     * @return codec defining the declared value representation, never null
     */
    public final FieldCodec<Value> codec() {
        return codec;
    }

    /**
     * Returns the live value (no copy, no lock).
     *
     * @return current live value without copying; null if allowed by the codec
     */
    public Value get() {
        return value;
    }

    /**
     * Installs {@code newValue}. Returns {@code true} when the value changed (by {@link
     * FieldCodec#areEqual}).
     *
     * @param newValue replacement value; copied through the field codec
     * @return true only when codec equality detects a value change
     */
    public boolean set(Value newValue) {
        if (codec.areEqual(value, newValue)) {
            return false;
        }
        value = codec.copy(newValue);
        return true;
    }

    /**
     * Atomically (for the calling thread) replaces the value with {@code remap.apply(live-copy)}.
     * Returns {@code true} on change.
     *
     * @param remap non-null transformation applied to a defensive copy of the current value
     * @return true only when codec equality detects a value change
     */
    public boolean update(UnaryOperator<Value> remap) {
        Objects.requireNonNull(remap, "remap");
        Value next = remap.apply(codec.copy(value));
        if (codec.areEqual(value, next)) {
            return false;
        }
        value = codec.copy(next);
        return true;
    }

    /**
     * Compares the live value against {@code snapshot} with codec equality.
     *
     * @param snapshot previously captured value to compare against
     * @return true if codec equality considers both values equal
     */
    public boolean isEqualTo(Value snapshot) {
        return codec.areEqual(value, snapshot);
    }

    /**
     * {@inheritDoc}
     *
     * @return diagnostic or stable identifier text, never null
     */
    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
