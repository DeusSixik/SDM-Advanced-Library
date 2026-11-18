package dev.sixik.sdmal.example.recipes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates custom application recipes without Minecraft world access.
 */
class StandaloneRecipesExampleTest {
    /**
     * A match requires both the indexed material and enough detached input items.
     */
    @Test
    void create_customRecipes_matchesMaterialAndCount() {
        var recipes = StandaloneRecipesExample.create();
        var match = recipes.first(StandaloneRecipesExample.PRESSING, new StandaloneRecipesExample.PressInput(1, 2));
        assertNotNull(match);
        assertEquals("sdmal_example:iron_plate", match.id());
        assertEquals(101, match.value().outputMaterial());
        assertNull(recipes.first(StandaloneRecipesExample.PRESSING, new StandaloneRecipesExample.PressInput(1, 1)));
        assertNull(recipes.first(StandaloneRecipesExample.PRESSING, new StandaloneRecipesExample.PressInput(3, 99)));
    }
}
