package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;

import net.minecraft.nbt.Tag;

import java.util.function.Function;

/**
 * Library-only ownership contract for built-ins and structural adapters. Writes return independent
 * tags; reads never mutate or retain caller-owned tags. Custom adapters are deliberately excluded
 * and keep their defensive-copy boundary.
 *
 * @param writer encoder producing exclusively owned tag graphs
 * @param reader decoder observing tags without changing or retaining them
 * @param <T> represented value type
 */
record OwnedNbtSerializer<T>(Function<T, Tag> writer, Function<Tag, T> reader)
        implements EcsSerializer<T> {
    /**
     * Encodes a non-null value into an independently owned tag graph.
     *
     * @param value protected source value
     * @return independent non-null tag
     */
    @Override
    public Tag write(T value) {
        return writer.apply(value);
    }

    /**
     * Decodes without modifying or retaining the supplied tag graph.
     *
     * @param tag source tag, not owned by this adapter
     * @return independent decoded value
     */
    @Override
    public T read(Tag tag) {
        return reader.apply(tag);
    }
}
