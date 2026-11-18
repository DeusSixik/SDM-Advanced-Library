package dev.sixik.sdmal.api.repository;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.sixik.sdmal.core.repository.NbtDocuments;

import net.minecraft.nbt.CompoundTag;

import java.util.Base64;

/**
 * Optional native NBT value codec. NBT files retain the native compound; JSON/SQL/Mongo retain a
 * lossless base64 representation. This preserves exact NBT numeric and array types.
 */
public interface NbtRepositoryCodec<V> extends RepositoryCodec<V> {
    /**
     * @param value source value
     * @return detached native payload
     */
    CompoundTag writeNbt(V value);

    /**
     * @param tag owned decoded payload
     * @return detached application value
     */
    V readNbt(CompoundTag tag);

    /**
     * {@inheritDoc}
     */
    @Override
    default String encode(V value) {
        return new JsonPrimitive(
                Base64.getEncoder().encodeToString(NbtDocuments.encode(writeNbt(value))))
                .toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default V decode(String json) {
        return readNbt(
                NbtDocuments.decode(
                        Base64.getDecoder().decode(JsonParser.parseString(json).getAsString())));
    }
}
