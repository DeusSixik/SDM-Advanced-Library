package dev.sixik.sdmal.core.ecs.codecs;

import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Bridges the NBT accessor changes while preserving the original on-disk ECS format. */
public final class NbtAccess {
    /** Prevents utility instantiation. */
    private NbtAccess() {}

    /**
     * Reads a numeric tag without depending on optional CompoundTag accessors.
     * @param tag numeric tag, non-null
     * @return boxed numeric value, never null
     */
    public static Number number(NumericTag tag) {
        //? if >=1.21.5 {
        /*return tag.box();
        *///?} else {
        return tag.getAsNumber();
        //?}
    }

    /**
     * Reads the unquoted contents of a string tag.
     * @param tag string tag, non-null
     * @return stored string, never null
     */
    public static String string(StringTag tag) {
        //? if >=1.21.5 {
        /*return tag.value();
        *///?} else {
        return tag.getAsString();
        //?}
    }

    /**
     * Returns a string value with a fallback for absent or incorrectly typed tags.
     * @param tag source tag, possibly null
     * @param fallback value used if the tag is not a string, possibly null
     * @return stored text or fallback
     */
    public static String string(Tag tag, String fallback) {
        return tag instanceof StringTag text ? string(text) : fallback;
    }

    /**
     * Enumerates stored compound keys.
     * @param tag source compound, non-null
     * @return live key view, not for modification
     */
    public static Set<String> keys(CompoundTag tag) {
        //? if >=1.21.5 {
        /*return tag.keySet();
        *///?} else {
        return tag.getAllKeys();
        //?}
    }

    /**
     * Stores a UUID using Minecraft's four-int representation on every supported version.
     * @param tag destination compound, non-null
     * @param key field name, non-null
     * @param value UUID to encode, non-null
     */
    public static void putUuid(CompoundTag tag, String key, UUID value) {
        long most = value.getMostSignificantBits();
        long least = value.getLeastSignificantBits();
        tag.putIntArray(key, new int[] {(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least});
    }

    /**
     * Reads the standard four-int UUID representation.
     * @param tag source compound, non-null
     * @param key field name, non-null
     * @param fallback value for absent or malformed data, possibly null
     * @return decoded UUID or fallback
     */
    public static UUID uuid(CompoundTag tag, String key, UUID fallback) {
        if (!(tag.get(key) instanceof IntArrayTag array)) return fallback;
        int[] parts = array.getAsIntArray();
        if (parts.length != 4) return fallback;
        return new UUID(((long) parts[0] << 32) | (parts[1] & 0xffffffffL),
            ((long) parts[2] << 32) | (parts[3] & 0xffffffffL));
    }
}
