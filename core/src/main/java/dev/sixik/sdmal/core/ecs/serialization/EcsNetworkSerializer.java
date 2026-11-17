package dev.sixik.sdmal.core.ecs.serialization;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Encodes non-null values directly into binary packets, without an NBT persistence requirement.
 * Implementations must be stateless/thread-safe and may only read/write their own payload. The framework
 * handles nullable references. A class may implement both this interface and EcsSerializer when needed.
 * @param <T> concrete value type, including generic arguments
 */
public interface EcsNetworkSerializer<T> {
    /**
     * Writes a value without modifying it or releasing/clearing the caller's buffer.
     * @param buffer destination buffer
     * @param value non-null value protected by the caller's state discipline
     */
    void write(FriendlyByteBuf buffer, T value);

    /**
     * Reads exactly one value and rejects malformed data; component writes occur only after complete decoding.
     * @param buffer bounded payload buffer, not to be retained or released
     * @return non-null reconstructed value
     * @throws EcsSerializationException if the payload cannot be decoded
     */
    T read(FriendlyByteBuf buffer);

    /**
     * Identifies this exact binary format. Increment its version when changing byte layout or meaning.
     * @return stable non-blank schema identifier, independent of adapter class names
     */
    String schemaName();
}
