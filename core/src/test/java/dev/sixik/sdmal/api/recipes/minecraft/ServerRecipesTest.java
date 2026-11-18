package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeType;
import dev.sixik.sdmal.core.recipes.NativeRecipes;
import dev.sixik.sdmal.core.utils.ObjectArrayView;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.CookingBookCategory;
//?} else {
/*import net.minecraft.core.Registry;
*///?}
//? if >=1.20.2
//import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exercises actual vanilla cooking serialization/matching plus independently controlled server reloads.
 */
class ServerRecipesTest {
    /**
     * Owner thread used to reject worker access to native mutable data.
     */
    private Thread owner;
    /**
     * Mocked native lifecycle while recipe/ingredient/stack implementations remain real.
     */
    private MinecraftServer server;
    /**
     * Current native recipe manager source; identity matters for automatic publication.
     */
    private net.minecraft.world.item.crafting.RecipeManager source;
    /**
     * Real bootstrap registries used by stack codecs on all supported versions.
     */
    private RegistryAccess.Frozen registries;
    /**
     * Server-local prepared recipe service under test.
     */
    private ServerRecipes service;

    /**
     * Initializes registry-backed Minecraft ingredient and stack codecs.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //? if >=26.3 {
        /*var lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        *///?} else if >=1.21.2 {
        /*var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        *///?}
        //? if >=1.21.2 {
        /*for (var components : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)) {
            components.apply();
        }
        *///?}
    }

    /**
     * Creates a server whose native data is readable only from the calling test thread.
     */
    @BeforeEach
    void setup() {
        owner = Thread.currentThread();
        server = mock(MinecraftServer.class);
        source = mock(net.minecraft.world.item.crafting.RecipeManager.class);
        //? if >=1.19.3 {
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        //?} else {
        /*registries = RegistryAccess.fromRegistryOfRegistries(Registry.REGISTRY);
        *///?}
        when(server.isSameThread()).thenAnswer(ignored -> Thread.currentThread() == owner);
        when(server.getRecipeManager()).thenReturn(source);
        when(server.registryAccess()).thenReturn(registries);
        doReturn(ObjectArrayView.of()).when(source).getRecipes();
        service = new ServerRecipes(server);
    }

    /**
     * Expanded ingredients are detached primitive data; native result NBT/components remain exact.
     */
    @Test
    void refresh_vanillaSmelting_freezesInputOutputTimeAndExperience() {
        ItemStack output = new ItemStack(Items.IRON_SWORD, 1);
        output.setDamageValue(12);
        var recipe = smelting("test:iron", Ingredient.of(Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE), output);
        load(source, "test:iron", recipe);
        var report = service.refresh();
        assertEquals(1, report.snapshot().size());
        assertTrue(report.unsupportedIds().isEmpty());
        var input = SingleItemInput.capture(new ItemStack(Items.IRON_ORE, 4));
        var found = report.snapshot().first(CookingRecipes.SMELTING, input);
        assertNotNull(found);
        assertEquals("test:iron", found.id());
        assertEquals(200, found.value().cookingTicks());
        assertEquals(0.7f, found.value().experience(), 0.0f);
        assertNotNull(report.snapshot().first(CookingRecipes.SMELTING,
                SingleItemInput.capture(new ItemStack(Items.DEEPSLATE_IRON_ORE))));
        assertNull(report.snapshot().first(CookingRecipes.SMELTING, SingleItemInput.capture(new ItemStack(Items.DIRT))));
        assertNull(report.snapshot().first(CookingRecipes.SMELTING, SingleItemInput.capture(ItemStack.EMPTY)));
        assertNull(report.snapshot().lookup(CookingRecipes.BLASTING));
        ItemStack restored = found.value().output().createStack(server);
        assertSame(Items.IRON_SWORD, restored.getItem());
        assertEquals(12, restored.getDamageValue());
        restored.setDamageValue(29);
        output.setDamageValue(41);
        assertEquals(12, found.value().output().createStack(server).getDamageValue());
        int[] keys = found.value().inputItems();
        java.util.Arrays.fill(keys, -1);
        assertNotNull(report.snapshot().first(CookingRecipes.SMELTING, input));
    }

    /**
     * Replacement publishes all entries together and invalidates old generation checks.
     */
    @Test
    void refresh_reloadedRecipes_keepsOldSnapshotAndInvalidatesApplication() {
        load(source, "test:old", smelting("test:old", Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT)));
        service.refreshIfChanged();
        var old = service.snapshot();
        assertTrue(service.isCurrent(old));
        var changed = mock(net.minecraft.world.item.crafting.RecipeManager.class);
        load(changed, "test:new", smelting("test:new", Ingredient.of(Items.GOLD_ORE), new ItemStack(Items.GOLD_INGOT)));
        when(server.getRecipeManager()).thenReturn(changed);
        service.refreshIfChanged();
        assertFalse(service.isCurrent(old));
        assertNotNull(old.byId("test:old"));
        assertNull(old.byId("test:new"));
        assertNull(service.snapshot().byId("test:old"));
        assertNotNull(service.snapshot().byId("test:new"));
        var current = service.snapshot();
        service.refreshIfChanged();
        assertSame(current, service.snapshot());
        verify(changed, times(1)).getRecipes();
    }

    /**
     * In-place programmatic edits need explicit refresh, even when native manager identity is unchanged.
     */
    @Test
    void refresh_sameNativeManager_explicitRefreshRebuildsGeneration() {
        service.refresh();
        var old = service.snapshot();
        load(source, "test:added", smelting("test:added", Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT)));
        service.refresh();
        assertFalse(service.isCurrent(old));
        assertNotNull(service.snapshot().byId("test:added"));
    }

    /**
     * Unknown mod recipes appear in the coverage report rather than invoking native matches on workers.
     */
    @Test
    void refresh_unsupportedNativeClass_reportsIdAndDoesNotApproximate() {
        Recipe<?> unknown = mock(Recipe.class);
        load(source, "test:unknown", unknown);
        var report = service.refresh();
        assertTrue(report.snapshot().entries().isEmpty());
        assertEquals(ObjectArrayView.of("test:unknown"), report.unsupportedIds());
        verify(unknown, never()).getType();
        assertThrows(UnsupportedOperationException.class, () -> report.unsupportedIds().clear());
    }

    /**
     * Exact native-class adapters may provide application-specific immutable match data.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refresh_customAdapter_preparesTypedDataWithoutRetainingNativeRecipe() {
        Recipe<?> nativeRecipe = mock(Recipe.class);
        var type = RecipeType.<Integer, Integer>of("test:custom", Integer::equals);
        service.registerAdapter((Class) nativeRecipe.getClass(), (id, recipe, lookup) -> new RecipeEntry<>(id, type, 42));
        load(source, "test:custom_recipe", nativeRecipe);
        service.refresh();
        assertEquals(42, service.snapshot().first(type, 42).value());
        assertNull(service.snapshot().first(type, 41));
        assertThrows(IllegalArgumentException.class,
                () -> service.registerAdapter((Class) nativeRecipe.getClass(), (id, recipe, lookup) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> service.registerAdapter(SmeltingRecipe.class, (id, recipe, lookup) -> null));
    }

    /**
     * A bad adapter cannot publish a partial generation or allow stale worker application.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refresh_adapterFailure_retainsReadableGenerationButDisablesApplication() {
        service.refresh();
        var old = service.snapshot();
        Recipe<?> nativeRecipe = mock(Recipe.class);
        service.registerAdapter((Class) nativeRecipe.getClass(), (id, recipe, lookup) -> {
            throw new IllegalStateException("fixture failure");
        });
        load(source, "test:bad", nativeRecipe);
        assertThrows(IllegalStateException.class, service::refresh);
        assertSame(old, service.snapshot());
        assertFalse(service.isCurrent(old));
        doReturn(ObjectArrayView.of()).when(source).getRecipes();
        service.refresh();
        assertTrue(service.isCurrent(service.snapshot()));
    }

    /**
     * Adapters cannot accidentally corrupt identity by exporting another recipe's id.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refresh_adapterChangesId_rejectsBeforePublication() {
        Recipe<?> nativeRecipe = mock(Recipe.class);
        var type = RecipeType.<Integer, Integer>of("test:custom", Integer::equals);
        service.registerAdapter((Class) nativeRecipe.getClass(),
                (id, recipe, lookup) -> new RecipeEntry<>("test:wrong", type, 1));
        load(source, "test:original", nativeRecipe);
        var previous = service.snapshot();
        assertThrows(IllegalArgumentException.class, service::refresh);
        assertSame(previous, service.snapshot());
    }

    /**
     * Worker queries succeed while owner-only native operations fail before touching Minecraft data.
     *
     * @throws Exception if worker validation fails or times out
     */
    @Test
    void workerQueries_parallelReadAllowed_nativeCaptureAndOutputDecodeRejected() throws Exception {
        load(source, "test:iron", smelting("test:iron", Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT)));
        service.refresh();
        var snapshot = service.snapshot();
        var input = SingleItemInput.capture(new ItemStack(Items.IRON_ORE));
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new it.unimi.dsi.fastutil.objects.ObjectArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 4; index++) {
                tasks.add(executor.submit(() -> {
                    for (int search = 0; search < 1000; search++) {
                        assertEquals("test:iron", snapshot.first(CookingRecipes.SMELTING, input).id());
                    }
                    assertThrows(IllegalStateException.class, service::refresh);
                    assertThrows(IllegalStateException.class, () -> ServerRecipes.get(server));
                    assertThrows(IllegalStateException.class,
                            () -> snapshot.first(CookingRecipes.SMELTING, input).value().output().createStack(server));
                }));
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Server shutdown invalidates application checks while old immutable reads still work.
     */
    @Test
    void close_invalidatesGenerationAndRejectsFurtherNativeWork() {
        service.refresh();
        var old = service.snapshot();
        service.close();
        service.close();
        assertFalse(service.isCurrent(old));
        assertSame(old, service.snapshot());
        assertThrows(IllegalStateException.class, service::refresh);
        assertThrows(IllegalStateException.class, service::refreshIfChanged);
    }

    /**
     * Immutable primitive cooking contracts reject invalid machine metadata.
     */
    @Test
    void cookingData_invalidMetadata_rejects() {
        var output = new FrozenItemStack(new ItemStack(Items.IRON_INGOT), registries);
        assertThrows(IllegalArgumentException.class, () -> new CookingRecipe(new int[] {-1}, output, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new CookingRecipe(new int[] {1}, output, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CookingRecipe(new int[] {1}, output, 20, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new CookingRecipe(new int[] {1}, output, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> new FrozenItemStack(ItemStack.EMPTY, registries));
        assertThrows(IllegalArgumentException.class, () -> new SingleItemInput(-2, 1));
        assertThrows(IllegalArgumentException.class, () -> new SingleItemInput(1, -1));
        assertEquals(NativeRecipes.itemId(Items.IRON_ORE), SingleItemInput.capture(new ItemStack(Items.IRON_ORE)).itemId());
    }

    /**
     * Creates a real vanilla smelting recipe across constructor changes.
     *
     * @param id native recipe id on older versions
     * @param ingredient real native input
     * @param output real native output
     * @return exact vanilla smelting implementation
     */
    static SmeltingRecipe smelting(String id, Ingredient ingredient, ItemStack output) {
        //? if >=1.21.2 {
        /*return new SmeltingRecipe(new Recipe.CommonInfo(true),
                new net.minecraft.world.item.crafting.AbstractCookingRecipe.CookingBookInfo(CookingBookCategory.MISC, ""),
                ingredient, net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(output), 0.7f, 200);
        *///?} else if >=1.20.2 {
        /*return new SmeltingRecipe("", CookingBookCategory.MISC, ingredient, output, 0.7f, 200);
        *///?} else if >=1.19.3 {
        return new SmeltingRecipe(java.util.Objects.requireNonNull(ResourceLocation.tryParse(id)), "",
                CookingBookCategory.MISC, ingredient, output, 0.7f, 200);
        //?} else {
        /*return new SmeltingRecipe(java.util.Objects.requireNonNull(ResourceLocation.tryParse(id)), "",
                ingredient, output, 0.7f, 200);
        *///?}
    }

    /**
     * Supplies one native recipe/holder to an independently controlled manager lifecycle.
     *
     * @param source native manager mock
     * @param id stable source recipe id
     * @param recipe real or unsupported native recipe
     */
    private static void load(net.minecraft.world.item.crafting.RecipeManager source, String id, Recipe<?> recipe) {
        //? if >=1.21.2 {
        /*var entry = new RecipeHolder<>(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.RECIPE,
                java.util.Objects.requireNonNull(ResourceLocation.tryParse(id))), recipe);
        *///?} else if >=1.20.2 {
        /*var entry = new RecipeHolder<>(java.util.Objects.requireNonNull(ResourceLocation.tryParse(id)), recipe);
        *///?} else {
        var entry = recipe;
        if (mockingDetails(recipe).isMock()) {
            doReturn(java.util.Objects.requireNonNull(ResourceLocation.tryParse(id))).when(recipe).getId();
        }
        //?}
        doReturn(ObjectArrayView.of(entry)).when(source).getRecipes();
    }
}
