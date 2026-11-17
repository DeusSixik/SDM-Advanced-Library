package dev.sixik.sdmal.core.ecs.serialization;

import net.minecraft.nbt.Tag;

/**
 * Converts non-null values to standalone NBT without requiring network, revision or equality methods.
 * Implementations must be stateless/thread-safe. The framework owns null envelopes and defensive NBT copies.
 *
 * @param <T> concrete value type, including any generic arguments
 */
public interface EcsSerializer<T> {
    /**
     * Encodes one non-null value without changing it.
     *
     * @param value source value; mutable values must be protected by the caller's state lock
     * @return non-null NBT tag; EndTag is not permitted
     * @throws EcsSerializationException if the value cannot be represented
     */
    Tag write(T value);

    /**
     * Decodes a value or rejects malformed input instead of silently using defaults.
     *
     * @param tag owned, non-null NBT copy which the adapter may consume
     * @return non-null reconstructed value
     * @throws EcsSerializationException if the tag cannot be decoded
     */
    T read(Tag tag);
}
