package dev.sixik.sdmal.core.ecs.serialization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Cached component plan; missing keys retain constructor defaults and malformed data causes no
 * field writes.
 *
 * @param members immutable persisted members in stable key order
 */
record EcsComponentSchema(EcsMember[] members) {
    /**
     * Sentinel distinguishes missing keys from successfully decoded null values.
     */
    private static final Object ABSENT = new Object();

    /**
     * Stages all encoded members before updating the destination compound.
     *
     * @param component source component whose state is protected by its caller
     * @param target    owned output compound, possibly containing unknown keys
     */
    void write(Object component, CompoundTag target) {
        Tag[] staged = new Tag[members.length];
        for (int index = 0; index < members.length; index++)
            staged[index] = members[index].write(component);
        for (int index = 0; index < members.length; index++)
            target.put(members[index].key(), staged[index]);
    }

    /**
     * Decodes every present field before committing; unannotated/missing fields are untouched.
     *
     * @param component target component whose state is protected by its caller
     * @param source    caller-owned input, never mutated
     */
    void read(Object component, CompoundTag source) {
        Object[] staged = new Object[members.length];
        for (int index = 0; index < members.length; index++) {
            EcsMember member = members[index];
            Tag tag = source.get(member.key());
            staged[index] = tag == null ? ABSENT : member.read(tag);
        }
        for (int index = 0; index < members.length; index++)
            if (staged[index] != ABSENT) members[index].set(component, staged[index]);
    }
}
