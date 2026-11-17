package dev.sixik.sdmal.core.ecs.serialization;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Type;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Immutable field access plan, built once during schema validation.
 * @param key stable non-blank NBT key
 * @param path declaring Java class/member for diagnostics
 * @param access cached private field handle
 * @param type fully resolved declared type
 * @param serializer non-null value adapter
 * @param volatileAccess whether Java's volatile field access semantics must be preserved
 */
record EcsMember(String key, String path, VarHandle access, Type type, EcsSerializer<?> serializer, boolean volatileAccess) {
    /**
     * Captures one member into its nullable envelope.
     * @param owner source component or record
     * @return owned envelope
     */
    CompoundTag write(Object owner) {
        try { return EcsValues.write(serializer, volatileAccess ? access.getVolatile(owner) : access.get(owner)); }
        catch (Throwable failure) { throw EcsSerializationException.at(path, failure); }
    }

    /**
     * Validates and decodes one member before any component mutation occurs.
     * @param tag member envelope, non-null
     * @return boxed decoded value, possibly null for reference fields
     */
    Object read(Tag tag) {
        try {
            Object value = EcsValues.read(serializer, tag);
            if (value == null && EcsTypes.raw(type).isPrimitive())
                throw new EcsSerializationException("Primitive member cannot be null");
            if (value != null && !EcsTypes.raw(EcsTypes.boxed(type)).isInstance(value))
                throw new EcsSerializationException("Adapter returned incompatible value " + value.getClass().getName());
            return value;
        } catch (Throwable failure) { throw EcsSerializationException.at(path, failure); }
    }

    /**
     * Commits an already validated component field value using its declared memory access semantics.
     * @param owner destination component
     * @param value decoded compatible value
     */
    void set(Object owner, Object value) {
        if (volatileAccess) access.setVolatile(owner, value);
        else access.set(owner, value);
    }
}
