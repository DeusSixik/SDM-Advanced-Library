package dev.sixik.sdmal.core.recipes;

import dev.sixik.sdmal.api.recipes.minecraft.ServerRecipes;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-server mixin bridge; stores no process-wide server or Level references.
 */
@ApiStatus.Internal
public interface ServerRecipeAccess {
    /**
     * @return recipe service owned by this exact server instance
     */
    ServerRecipes sdmal$recipes();
}
