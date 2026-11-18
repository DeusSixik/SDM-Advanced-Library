package dev.sixik.sdmal.api.recipes.minecraft;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable vanilla cooking data containing expanded ingredient ids, encoded output, time, and XP.
 * Tags are expanded before publication, so queries never access changing registry bindings.
 */
public final class CookingRecipe {
    /**
     * Sorted independently owned accepted item ids; never exposed directly.
     */
    private final int[] inputItems;
    /**
     * Encoded output with worker-readable primitive metadata.
     */
    private final FrozenItemStack output;
    /**
     * Positive cooking duration in game ticks.
     */
    private final int cookingTicks;
    /**
     * Nonnegative finite experience awarded per completed operation.
     */
    private final float experience;

    /**
     * Copies ingredient keys and validates detached cooking metadata.
     *
     * @param inputItems   exhaustive native ingredient item ids; ownership is not retained
     * @param output       frozen complete native output
     * @param cookingTicks positive duration in ticks
     * @param experience   nonnegative finite XP per operation
     */
    public CookingRecipe(int[] inputItems, FrozenItemStack output, int cookingTicks, float experience) {
        this.inputItems = Objects.requireNonNull(inputItems, "inputItems").clone();
        this.output = Objects.requireNonNull(output, "output");
        if (cookingTicks <= 0 || !Float.isFinite(experience) || experience < 0) {
            throw new IllegalArgumentException("Cooking ticks must be positive; experience finite and >= 0");
        }
        for (int id : this.inputItems) {
            if (id < 0) {
                throw new IllegalArgumentException("Ingredient item ids must be nonnegative");
            }
        }
        Arrays.sort(this.inputItems);
        this.cookingTicks = cookingTicks;
        this.experience = experience;
    }

    /**
     * @return defensive ingredient key copy, intended for index rebuild and diagnostics
     */
    public int[] inputItems() {
        return inputItems.clone();
    }

    /**
     * @return immutable result descriptor, with owner-thread stack creation
     */
    public FrozenItemStack output() {
        return output;
    }

    /**
     * @return positive cooking duration in ticks
     */
    public int cookingTicks() {
        return cookingTicks;
    }

    /**
     * @return nonnegative finite XP per operation
     */
    public float experience() {
        return experience;
    }

    /**
     * Performs only primitive lookup, without native Ingredient.test, Level or ItemStack access.
     *
     * @param input detached owned input
     * @return true if at least one accepted item is present
     */
    public boolean matches(SingleItemInput input) {
        return input.count() > 0 && Arrays.binarySearch(inputItems, input.itemId()) >= 0;
    }
}
