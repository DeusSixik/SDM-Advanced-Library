package dev.sixik.sdmal.example.recipes;

import dev.sixik.sdmal.api.ecs.EcsContext;
import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeManager;
import dev.sixik.sdmal.api.recipes.minecraft.CookingRecipe;
import dev.sixik.sdmal.api.recipes.minecraft.CookingRecipes;
import dev.sixik.sdmal.api.recipes.minecraft.FrozenItemStack;
import dev.sixik.sdmal.api.recipes.minecraft.ServerRecipes;
import dev.sixik.sdmal.api.recipes.minecraft.SingleItemInput;
import dev.sixik.sdmal.core.recipes.ServerRecipeAccess;
import dev.sixik.sdmal.core.utils.ObjectArrayView;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies worker-only recipe planning and stale input/generation rejection during owner apply.
 */
class EcsRecipeSearchComponentTest {
    /**
     * Initializes native classes referenced by the server mocks without starting a world.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A detached worker input needs neither live component state nor world-context access.
     *
     * @throws Exception if computation fails or the worker does not finish
     */
    @Test
    void compute_worker_usesOnlyCapturedImmutableData() throws Exception {
        var manager = new RecipeManager();
        var recipe = new RecipeEntry<>("test:iron", CookingRecipes.SMELTING,
                new CookingRecipe(new int[] {42}, mock(FrozenItemStack.class), 200, 0.7f));
        var snapshot = manager.replaceAll(ObjectArrayView.of(recipe));
        var input = new EcsRecipeSearchComponent.Input(snapshot, new SingleItemInput(42, 3), 7);
        var component = new EcsRecipeSearchComponent();
        var context = mock(EcsContext.class);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> component.compute(input, context)).get(10, TimeUnit.SECONDS);
            assertSame(snapshot, result.recipes());
            assertSame(recipe, result.recipe());
            assertEquals(7, result.inputRevision());
            assertNull(component.selection());
            verifyNoInteractions(context);
        } finally {
            executor.shutdownNow();
        }
        assertNull(component.compute(null, context));
    }

    /**
     * Reload and authoritative input changes invalidate a previously computed machine selection.
     */
    @Test
    void apply_reloadOrInputChange_discardsOldSelection() {
        var server = mock(MinecraftServer.class, withSettings().extraInterfaces(ServerRecipeAccess.class));
        var nativeRecipes = mock(net.minecraft.world.item.crafting.RecipeManager.class);
        when(server.isSameThread()).thenReturn(true);
        when(server.getRecipeManager()).thenReturn(nativeRecipes);
        doReturn(ObjectArrayView.of()).when(nativeRecipes).getRecipes();
        var service = new ServerRecipes(server);
        when(((ServerRecipeAccess) server).sdmal$recipes()).thenReturn(service);
        var level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        var context = mock(EcsContext.class);
        when(context.level()).thenReturn(level);
        var component = new EcsRecipeSearchComponent();

        var input = component.capture(context);
        var result = component.compute(input, context);
        component.apply(result, context);
        assertSame(result, component.selection());

        service.refresh();
        component.apply(result, context);
        assertNull(component.selection());

        result = component.compute(component.capture(context), context);
        component.apply(result, context);
        assertSame(result, component.selection());
        component.setInput(ItemStack.EMPTY);
        assertNull(component.selection());
        component.apply(result, context);
        assertNull(component.selection());
    }
}
