package dev.sixik.sdmal.example.recipes;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.EcsContext;
import dev.sixik.sdmal.api.ecs.SnapshotTickableComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeSnapshot;
import dev.sixik.sdmal.api.recipes.minecraft.CookingRecipe;
import dev.sixik.sdmal.api.recipes.minecraft.CookingRecipes;
import dev.sixik.sdmal.api.recipes.minecraft.ServerRecipes;
import dev.sixik.sdmal.api.recipes.minecraft.SingleItemInput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Component-local ECS job demonstrating safe native recipe planning. The machine supplies its
 * input on the owner thread; compute searches only immutable data. This example selects a recipe,
 * but does not consume items, simulate furnace fuel/progress, or insert output into an inventory.
 */
@EcsComponent(id = "sdmal_example:recipe_search")
public final class EcsRecipeSearchComponent extends Component
        implements SnapshotTickableComponent<EcsRecipeSearchComponent.Input, EcsRecipeSearchComponent.Selection> {
    /**
     * Detached primitive input updated only on the owning ECS thread.
     */
    private SingleItemInput input = new SingleItemInput(-1, 0);
    /**
     * Monotonic local input revision preventing stale job application after input changes.
     */
    private long inputRevision;
    /**
     * Last validated recipe selection; never a mutable native Minecraft Recipe.
     */
    private Selection selection;

    /**
     * Creates an empty machine recipe planner, automatically registered by the ECS processor.
     */
    public EcsRecipeSearchComponent() {
    }

    /**
     * Captures a machine's input before jobs are dispatched. Call whenever the authoritative input
     * changes, including after consumption. No mutable stack is retained by this component.
     *
     * @param stack machine input, read exclusively on its owner thread
     */
    public void setInput(ItemStack stack) {
        checkOwnerThread();
        input = SingleItemInput.capture(stack);
        inputRevision++;
        selection = null;
    }

    /**
     * Reads the server service only on the owner thread and hands immutable data to the worker.
     *
     * @param context live world context available only during capture/apply
     * @return detached job input, or null for client worlds
     */
    @Override
    public Input capture(EcsContext context) {
        checkOwnerThread();
        if (!(context.level() instanceof ServerLevel level)) {
            return null;
        }
        return new Input(ServerRecipes.get(level.getServer()).snapshot(), input, inputRevision);
    }

    /**
     * Performs a primitive-indexed lookup without accessing any live component fields or Level.
     *
     * @param input   immutable captured job data, or null on clients
     * @param context deferred command context, unused for pure search
     * @return captured generation, input revision and immutable matching recipe, or null
     */
    @Override
    public Selection compute(Input input, EcsContext context) {
        if (input == null) {
            return null;
        }
        return new Selection(input.recipes(), input.inputRevision(),
                input.recipes().first(CookingRecipes.SMELTING, input.items()));
    }

    /**
     * Publishes only results whose recipe generation and input revision are still current.
     *
     * @param result  immutable job result
     * @param context owner-thread world context
     */
    @Override
    public void apply(Selection result, EcsContext context) {
        checkOwnerThread();
        selection = null;
        if (result == null || result.inputRevision() != inputRevision
                || !(context.level() instanceof ServerLevel level)) {
            return;
        }
        if (!ServerRecipes.get(level.getServer()).isCurrent(result.recipes())) {
            return;
        }
        selection = result;
    }

    /**
     * Reads the current selection for machine progress/output-capacity checks on the owner thread.
     * Revalidate generation again at the actual item-consumption point if completion occurs later.
     *
     * @return immutable selected recipe, or null when unmatched/stale/not computed
     */
    public Selection selection() {
        checkOwnerThread();
        return selection;
    }

    /**
     * @param recipes       immutable prepared generation
     * @param items         immutable item/count input
     * @param inputRevision captured local input revision
     */
    public record Input(RecipeSnapshot recipes, SingleItemInput items, long inputRevision) {
    }

    /**
     * @param recipes       searched generation, used to reject results after /reload
     * @param inputRevision revision of the machine input searched by the worker
     * @param recipe        immutable prepared match, or null
     */
    public record Selection(RecipeSnapshot recipes, long inputRevision, RecipeEntry<CookingRecipe> recipe) {
    }
}
