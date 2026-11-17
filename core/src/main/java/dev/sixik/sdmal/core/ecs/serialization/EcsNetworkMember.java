package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;

import net.minecraft.network.FriendlyByteBuf;

import java.lang.invoke.VarHandle;

/**
 * Cached native binary member plan, independent of any NBT schema.
 *
 * @param key            stable wire key
 * @param path           Java field path for diagnostics
 * @param access         cached field handle
 * @param serializer     native binary adapter
 * @param nullable       whether the declared type is a reference
 * @param volatileAccess whether Java volatile semantics are required
 * @param primitiveKind  built-in primitive format code, or zero for boxed/custom adapters
 */
record EcsNetworkMember(
        String key,
        String path,
        VarHandle access,
        EcsNetworkSerializer<?> serializer,
        boolean nullable,
        boolean volatileAccess,
        int primitiveKind) {
    /**
     * Native boolean format from the immutable built-in catalog.
     */
    private static final int BOOLEAN = 1;

    /**
     * Native byte format from the immutable built-in catalog.
     */
    private static final int BYTE = 2;

    /**
     * Native short format from the immutable built-in catalog.
     */
    private static final int SHORT = 3;

    /**
     * Native char format from the immutable built-in catalog.
     */
    private static final int CHAR = 4;

    /**
     * Native int format from the immutable built-in catalog.
     */
    private static final int INT = 5;

    /**
     * Native long format from the immutable built-in catalog.
     */
    private static final int LONG = 6;

    /**
     * Native float format from the immutable built-in catalog.
     */
    private static final int FLOAT = 7;

    /**
     * Native double format from the immutable built-in catalog.
     */
    private static final int DOUBLE = 8;

    /**
     * Captures and encodes one field using the caller's state lock.
     *
     * @param owner  source instance
     * @param buffer destination buffer
     */
    void write(Object owner, FriendlyByteBuf buffer) {
        try {
            switch (primitiveKind) {
                case BOOLEAN -> buffer.writeByte(
                        (volatileAccess
                                ? (boolean) access.getVolatile(owner)
                                : (boolean) access.get(owner))
                                ? 1
                                : 0);
                case BYTE -> buffer.writeByte(
                        volatileAccess
                                ? (byte) access.getVolatile(owner)
                                : (byte) access.get(owner));
                case SHORT -> buffer.writeShort(
                        volatileAccess
                                ? (short) access.getVolatile(owner)
                                : (short) access.get(owner));
                case CHAR -> buffer.writeChar(
                        volatileAccess
                                ? (char) access.getVolatile(owner)
                                : (char) access.get(owner));
                case INT -> buffer.writeInt(
                        volatileAccess
                                ? (int) access.getVolatile(owner)
                                : (int) access.get(owner));
                case LONG -> buffer.writeLong(
                        volatileAccess
                                ? (long) access.getVolatile(owner)
                                : (long) access.get(owner));
                case FLOAT -> buffer.writeFloat(
                        volatileAccess
                                ? (float) access.getVolatile(owner)
                                : (float) access.get(owner));
                case DOUBLE -> buffer.writeDouble(
                        volatileAccess
                                ? (double) access.getVolatile(owner)
                                : (double) access.get(owner));
                default -> {
                    Object value = volatileAccess ? access.getVolatile(owner) : access.get(owner);
                    EcsNetworkValues.write(serializer, buffer, value, nullable);
                }
            }
        } catch (Throwable failure) {
            throw SerializationFailures.at(path, failure);
        }
    }

    /**
     * Selects a primitive format only for immutable built-in registrations without a field override.
     * Native writes match EcsNetworkBuiltins exactly; reference fields keep null handling and adapters.
     *
     * @param type declared Java field type
     * @return compact built-in format code, or zero for a reference type
     */
    static int primitiveKind(Class<?> type) {
        if (type == boolean.class) return BOOLEAN;
        if (type == byte.class) return BYTE;
        if (type == short.class) return SHORT;
        if (type == char.class) return CHAR;
        if (type == int.class) return INT;
        if (type == long.class) return LONG;
        if (type == float.class) return FLOAT;
        if (type == double.class) return DOUBLE;
        return 0;
    }

    /**
     * Decodes one field without changing the target component.
     *
     * @param buffer bounded source buffer
     * @return decoded value, possibly null
     */
    Object read(FriendlyByteBuf buffer) {
        try {
            return EcsNetworkValues.read(serializer, buffer, nullable);
        } catch (Throwable failure) {
            throw SerializationFailures.at(path, failure);
        }
    }

    /**
     * Installs a validated field value with the declared access mode.
     *
     * @param owner target instance
     * @param value compatible decoded value
     */
    void set(Object owner, Object value) {
        if (volatileAccess) access.setVolatile(owner, value);
        else access.set(owner, value);
    }

    /**
     * @return canonical key, nullability and adapter format description
     */
    String schemaName() {
        return EcsWireSchema.describe(
                "member/v1", key, nullable ? "reference" : "primitive", serializer.schemaName());
    }
}
