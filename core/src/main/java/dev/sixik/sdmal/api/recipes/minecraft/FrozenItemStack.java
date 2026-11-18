package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.core.recipes.NativeRecipes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Encoded immutable recipe output. Workers may inspect primitive metadata; decoding into a mutable
 * native ItemStack is explicitly owner-thread work. No mutable component or native recipe escapes.
 */
public final class FrozenItemStack {
    /**
     * Private encoded tag, never exposed or changed after capture.
     */
    private final Tag encoded;
    /**
     * Native output item id, stable for this server lifetime.
     */
    private final int itemId;
    /**
     * Captured positive output count.
     */
    private final int count;

    /**
     * Encodes all output components/NBT while registry access is safe on the owner thread.
     *
     * @param stack      nonempty native recipe result
     * @param registries currently bound owner-thread registry access
     */
    public FrozenItemStack(ItemStack stack, RegistryAccess registries) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(registries, "registries");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Prepared recipe output must not be empty");
        }
        itemId = NativeRecipes.itemId(stack.getItem());
        count = stack.getCount();
        encoded = ItemStack.CODEC.encodeStart(NativeRecipes.stackOps(registries), stack).result()
                .orElseThrow(() -> new IllegalArgumentException("Cannot encode prepared recipe output")).copy();
    }

    /**
     * @return native output item registry id, safe to read on workers
     */
    public int itemId() {
        return itemId;
    }

    /**
     * @return captured result count, safe to read on workers
     */
    public int count() {
        return count;
    }

    /**
     * Decodes an independent result, preserving every encoded native component/NBT field.
     * Validate recipe generation, machine input, output capacity, and permissions before consuming.
     *
     * @param server current server, on its owner thread
     * @return newly decoded independently mutable result stack
     * @throws IllegalStateException when called from a worker or decoding fails
     */
    public ItemStack createStack(MinecraftServer server) {
        NativeRecipes.checkOwner(server);
        return ItemStack.CODEC.parse(NativeRecipes.stackOps(server.registryAccess()), encoded.copy()).result()
                .orElseThrow(() -> new IllegalStateException("Cannot decode prepared recipe output"));
    }
}
