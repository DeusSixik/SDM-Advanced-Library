package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.api.recipes.RecipeType;

/**
 * Shared category tokens for prepared vanilla cooking recipes; not native Minecraft RecipeTypes.
 */
public final class CookingRecipes {
    /**
     * Furnace recipes, keyed by detached input item id.
     */
    public static final RecipeType<SingleItemInput, CookingRecipe> SMELTING = type("minecraft:smelting");
    /**
     * Blast furnace recipes, keyed by detached input item id.
     */
    public static final RecipeType<SingleItemInput, CookingRecipe> BLASTING = type("minecraft:blasting");
    /**
     * Smoker recipes, keyed by detached input item id.
     */
    public static final RecipeType<SingleItemInput, CookingRecipe> SMOKING = type("minecraft:smoking");
    /**
     * Campfire recipes, keyed by detached input item id.
     */
    public static final RecipeType<SingleItemInput, CookingRecipe> CAMPFIRE = type("minecraft:campfire_cooking");

    /**
     * Prevents construction.
     */
    private CookingRecipes() {
    }

    /**
     * @param id category id
     * @return primitive-indexed cooking type
     */
    private static RecipeType<SingleItemInput, CookingRecipe> type(String id) {
        return RecipeType.indexed(id, CookingRecipe::matches, SingleItemInput::itemId, CookingRecipe::inputItems);
    }
}
