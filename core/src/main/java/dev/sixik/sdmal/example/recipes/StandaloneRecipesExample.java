package dev.sixik.sdmal.example.recipes;

import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeManager;
import dev.sixik.sdmal.api.recipes.RecipeType;
import dev.sixik.sdmal.core.utils.ObjectArrayView;

/**
 * Minecraft-independent custom machine recipes using immutable records and primitive candidate keys.
 */
public final class StandaloneRecipesExample {
    /**
     * Shared pure recipe category: detached material id and available count determine matching.
     */
    public static final RecipeType<PressInput, PressRecipe> PRESSING = RecipeType.indexed(
            "sdmal_example:pressing", (recipe, input) -> recipe.material() == input.material()
                    && input.count() >= recipe.requiredCount(),
            PressInput::material, recipe -> new int[]{recipe.material()});

    /**
     * Prevents construction.
     */
    private StandaloneRecipesExample() {
    }

    /**
     * Builds a standalone manager during application setup; no server or Level is required.
     *
     * @return manager safely shareable with ECS compute jobs
     */
    public static RecipeManager create() {
        var recipes = new RecipeManager();
        recipes.replaceAll(ObjectArrayView.of(new RecipeEntry<>("sdmal_example:iron_plate", PRESSING,
                        new PressRecipe(1, 2, 101, 1, 40)),
                new RecipeEntry<>("sdmal_example:gold_plate", PRESSING, new PressRecipe(2, 2, 102, 1, 30))));
        return recipes;
    }

    /**
     * @param arguments unused command-line arguments
     */
    public static void main(String[] arguments) {
        RecipeManager manager = create();
        var captured = manager.snapshot();
        var match = captured.first(PRESSING, new PressInput(1, 8));
        if (match == null) {
            throw new IllegalStateException("Expected the iron plate recipe");
        }
        System.out.println("Matched " + match.id() + " in generation " + captured.generation());
        manager.clear();
        System.out.println("Old generation still readable: " + captured.first(PRESSING, new PressInput(1, 8)).id());
        System.out.println("Apply allowed after replacement: " + manager.isCurrent(captured));
    }

    /**
     * @param material primitive application material id
     * @param count    owned input count
     */
    public record PressInput(int material, int count) {
    }

    /**
     * @param material       input material id
     * @param requiredCount  input items consumed per operation
     * @param outputMaterial output material id
     * @param outputCount    output items produced
     * @param ticks          processing duration in ticks
     */
    public record PressRecipe(int material, int requiredCount, int outputMaterial, int outputCount, int ticks) {
    }
}
