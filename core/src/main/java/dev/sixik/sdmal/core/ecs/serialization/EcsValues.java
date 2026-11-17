package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.Tag;

/**
 * Handles unambiguous null envelopes and defensive copies at every annotated value boundary.
 */
final class EcsValues {
    /**
     * Prevents utility construction.
     */
    private EcsValues() {
    }

    /**
     * Encodes a nullable value; adapter payload keys cannot collide with the outer envelope.
     *
     * @param serializer resolved non-null value adapter
     * @param value      nullable field/element value
     * @return owned compound containing a byte presence flag and optional value
     */
    @SuppressWarnings("unchecked")
    static CompoundTag write(EcsSerializer<?> serializer, Object value) {
        CompoundTag result = new CompoundTag();
        result.putBoolean("$present", value != null);
        if (value != null) {
            Tag payload = ((EcsSerializer<Object>) serializer).write(value);
            if (payload == null || payload instanceof EndTag)
                throw new EcsSerializationException("Adapter must return a non-null non-EndTag payload");
            result.put("v", payload.copy());
        }
        return result;
    }

    /**
     * Decodes a nullable envelope without exposing caller-owned NBT to adapters.
     *
     * @param serializer resolved non-null value adapter
     * @param tag        required envelope
     * @return reconstructed value or null for an explicit absent value
     * @throws EcsSerializationException for malformed presence flags or adapter null results
     */
    static Object read(EcsSerializer<?> serializer, Tag tag) {
        if (!(tag instanceof CompoundTag envelope) || !(envelope.get("$present") instanceof ByteTag present))
            throw new EcsSerializationException("Expected annotated value envelope");
        byte flag = NbtAccess.number(present).byteValue();
        if (flag == 0) {
            if (envelope.get("v") != null) throw new EcsSerializationException("Null envelope contains a value");
            return null;
        }
        if (flag != 1 || envelope.get("v") == null) throw new EcsSerializationException("Invalid value presence flag");
        Object result = serializer.read(envelope.get("v").copy());
        if (result == null) throw new EcsSerializationException("Adapter returned null for a present value");
        return result;
    }
}
