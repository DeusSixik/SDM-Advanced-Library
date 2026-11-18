package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.core.recipes.NativeRecipes;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Detached item-only cooking input. Capture a live stack on its inventory owner thread before
 * dispatching jobs. Counts are nonnegative; -1 means no item. Components/NBT are intentionally not
 * consulted by vanilla cooking matching; custom ingredients require their own prepared adapter.
 *
 * @param itemId stable native item registry numeric id for this server lifetime, or -1
 * @param count  captured item count, at least zero
 */
public record SingleItemInput(int itemId, int count) {
    /**
     * Validates detached primitive query data.
     *
     * @param itemId native registry id, or -1 for empty
     * @param count  nonnegative stack count
     */
    public SingleItemInput {
        if (itemId < -1 || count < 0) {
            throw new IllegalArgumentException("Item id must be >= -1 and count >= 0");
        }
    }

    /**
     * Copies only registry identity and count; must be invoked by the live inventory's owner.
     *
     * @param stack exclusively readable stack on the calling owner thread
     * @return detached primitive input safe to pass to jobs
     */
    public static SingleItemInput capture(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.isEmpty() ? new SingleItemInput(-1, 0)
                : new SingleItemInput(NativeRecipes.itemId(stack.getItem()), stack.getCount());
    }
}
