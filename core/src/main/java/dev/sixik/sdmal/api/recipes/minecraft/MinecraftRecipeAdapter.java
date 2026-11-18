package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.api.recipes.RecipeEntry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Converts an exact native recipe class to detached data during owner-thread reload publication.
 * Implementations run only during rebuild. Do not return native recipes, live ingredient predicates,
 * mutable shared stacks, Level references, or callbacks consulting mutable world/registry state.
 *
 * @param <T> exact native recipe implementation class
 */
@FunctionalInterface
public interface MinecraftRecipeAdapter<T extends Recipe<?>> {
    /**
     * Prepares an immutable recipe entry without retaining the native source or registry context.
     *
     * @param id         complete native recipe id
     * @param recipe     native recipe, read exclusively on the server thread
     * @param registries current owner-thread registry access for encoding and tag expansion
     * @return detached entry with the same id, or null when this recipe is unsupported
     */
    RecipeEntry<?> prepare(String id, T recipe, RegistryAccess registries);
}
