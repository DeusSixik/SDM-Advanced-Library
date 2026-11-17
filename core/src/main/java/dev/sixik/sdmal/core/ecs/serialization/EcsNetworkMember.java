package dev.sixik.sdmal.core.ecs.serialization;

import java.lang.invoke.VarHandle;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Cached native binary member plan, independent of any NBT schema.
 * @param key stable wire key
 * @param path Java field path for diagnostics
 * @param access cached field handle
 * @param serializer native binary adapter
 * @param nullable whether the declared type is a reference
 * @param volatileAccess whether Java volatile semantics are required
 */
record EcsNetworkMember(String key, String path, VarHandle access, EcsNetworkSerializer<?> serializer,
                        boolean nullable, boolean volatileAccess) {
    /**
     * Captures and encodes one field using the caller's state lock.
     * @param owner source instance
     * @param buffer destination buffer
     */
    void write(Object owner, FriendlyByteBuf buffer) {
        try {
            Object value = volatileAccess ? access.getVolatile(owner) : access.get(owner);
            EcsNetworkValues.write(serializer, buffer, value, nullable);
        } catch (Throwable failure) { throw EcsSerializationException.at(path, failure); }
    }

    /**
     * Decodes one field without changing the target component.
     * @param buffer bounded source buffer
     * @return decoded value, possibly null
     */
    Object read(FriendlyByteBuf buffer) {
        try { return EcsNetworkValues.read(serializer, buffer, nullable); }
        catch (Throwable failure) { throw EcsSerializationException.at(path, failure); }
    }

    /**
     * Installs a validated field value with the declared access mode.
     * @param owner target instance
     * @param value compatible decoded value
     */
    void set(Object owner, Object value) {
        if (volatileAccess) access.setVolatile(owner, value);
        else access.set(owner, value);
    }

    /** @return canonical key, nullability and adapter format description */
    String schemaName() {
        return EcsWireSchema.describe("member/v1", key, nullable ? "reference" : "primitive", serializer.schemaName());
    }
}
