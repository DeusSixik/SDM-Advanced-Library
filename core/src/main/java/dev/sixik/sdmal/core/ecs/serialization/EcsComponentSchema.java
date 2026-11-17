package dev.sixik.sdmal.core.ecs.serialization;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Cached component plan; missing keys retain constructor defaults and malformed data causes no field writes.
 *
 * @param members immutable persisted members in stable key order
 */
record EcsComponentSchema(List<EcsMember> members) {
    /**
     * Stages all encoded members before updating the destination compound.
     *
     * @param component source component whose state is protected by its caller
     * @param target    owned output compound, possibly containing unknown keys
     */
    void write(Object component, CompoundTag target) {
        CompoundTag staged = new CompoundTag();
        for (EcsMember member : members) staged.put(member.key(), member.write(component));
        for (EcsMember member : members) target.put(member.key(), staged.get(member.key()));
    }

    /**
     * Decodes every present field before committing; unannotated/missing fields are untouched.
     *
     * @param component target component whose state is protected by its caller
     * @param source    caller-owned input, never mutated
     */
    void read(Object component, CompoundTag source) {
        Object[] staged = new Object[members.size()];
        boolean[] present = new boolean[members.size()];
        for (int index = 0; index < members.size(); index++) {
            EcsMember member = members.get(index);
            Tag tag = source.get(member.key());
            if (tag != null) {
                staged[index] = member.read(tag);
                present[index] = true;
            }
        }
        for (int index = 0; index < members.size(); index++)
            if (present[index]) members.get(index).set(component, staged[index]);
    }
}
