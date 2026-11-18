package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeManager;
import dev.sixik.sdmal.api.recipes.RecipeSnapshot;
import dev.sixik.sdmal.core.recipes.NativeRecipes;
import dev.sixik.sdmal.core.recipes.ServerRecipeAccess;
import dev.sixik.sdmal.core.utils.ObjectArrayView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Server-owned bridge from native datapack recipes to concurrent immutable searches. Capture the
 * service/snapshot on the owner thread, then query the snapshot on workers. Built-in adapters cover
 * exact vanilla cooking implementations with vanilla item/tag ingredients. Install adapters for
 * additional exact classes, including custom shaped, shapeless, dynamic, or machine recipes.
 */
public final class ServerRecipes {
    /**
     * Server whose native recipes may only be read on its owner thread.
     */
    private final MinecraftServer server;
    /**
     * Full-generation builder; never exposed for unrelated writes.
     */
    private final RecipeManager manager = new RecipeManager();
    /**
     * Exact native class adapters; accessed only by owner-thread setup/rebuild.
     */
    private final Reference2ObjectOpenHashMap<Class<?>, MinecraftRecipeAdapter<?>> adapters = new Reference2ObjectOpenHashMap<>();
    /**
     * Complete current generation and coverage, published through one volatile assignment.
     */
    private volatile RecipeReloadResult result = new RecipeReloadResult(manager.snapshot(), ObjectArrayView.of());
    /**
     * Native manager identity last imported successfully, or null before startup.
     */
    private Object importedSource;
    /**
     * Permanent server stop state; outstanding snapshots remain safely readable.
     */
    private volatile boolean closed;
    /**
     * False during/after a failed native rebuild, preventing application of stale worker results.
     */
    private volatile boolean usable;
    /**
     * Native source whose automatic import failed; explicit refresh or a new source may retry.
     */
    private Object failedSource;

    /**
     * Creates a server-local service with vanilla cooking adapters. Usually obtained through get().
     *
     * @param server exact owning server
     */
    @ApiStatus.Internal
    public ServerRecipes(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        adapters.put(SmeltingRecipe.class, (MinecraftRecipeAdapter<SmeltingRecipe>) (id, recipe, registries) ->
                NativeRecipes.cooking(id, recipe, CookingRecipes.SMELTING, registries));
        adapters.put(BlastingRecipe.class, (MinecraftRecipeAdapter<BlastingRecipe>) (id, recipe, registries) ->
                NativeRecipes.cooking(id, recipe, CookingRecipes.BLASTING, registries));
        adapters.put(SmokingRecipe.class, (MinecraftRecipeAdapter<SmokingRecipe>) (id, recipe, registries) ->
                NativeRecipes.cooking(id, recipe, CookingRecipes.SMOKING, registries));
        adapters.put(CampfireCookingRecipe.class, (MinecraftRecipeAdapter<CampfireCookingRecipe>) (id, recipe, registries) ->
                NativeRecipes.cooking(id, recipe, CookingRecipes.CAMPFIRE, registries));
    }

    /**
     * Obtains the service and ensures initial import before dispatching jobs. Invoke during ECS
     * capture/setup on the server thread; workers retain the returned service/snapshot instead.
     *
     * @param server native owning server, on its owner thread
     * @return per-server prepared recipe service
     */
    public static ServerRecipes get(MinecraftServer server) {
        NativeRecipes.checkOwner(server);
        if (!(server instanceof ServerRecipeAccess access)) {
            throw new IllegalStateException("SDMAL server recipe mixin is not installed");
        }
        ServerRecipes service = access.sdmal$recipes();
        service.refreshIfChanged();
        return service;
    }

    /**
     * @return coherent immutable generation, safe to capture/read on any thread
     */
    public RecipeSnapshot snapshot() {
        return result.snapshot();
    }

    /**
     * @return coverage associated with exactly one published generation
     */
    public RecipeReloadResult coverage() {
        return result;
    }

    /**
     * Tests whether a worker's generation still belongs to current server data. Call during owner
     * application, where reload publication cannot race with the following inventory checks/write.
     *
     * @param snapshot captured job generation
     * @return true only for the currently published server-local generation
     */
    public boolean isCurrent(RecipeSnapshot snapshot) {
        return !closed && usable && result.snapshot() == snapshot;
    }

    /**
     * Adds a freeze adapter for an exact native class; subclasses never silently inherit a matcher.
     * Call refresh afterwards when recipes are already loaded. Existing adapters cannot be replaced.
     * Register during server setup before beginning simulation jobs.
     *
     * @param nativeType exact native implementation class
     * @param adapter    owner-thread freeze operation, returning deeply immutable prepared data
     * @param <T>        exact native recipe type
     */
    public <T extends Recipe<?>> void registerAdapter(Class<T> nativeType, MinecraftRecipeAdapter<T> adapter) {
        checkOpen();
        if (adapters.putIfAbsent(Objects.requireNonNull(nativeType, "nativeType"),
                Objects.requireNonNull(adapter, "adapter")) != null) {
            throw new IllegalArgumentException("Recipe adapter already registered for " + nativeType.getName());
        }
    }

    /**
     * Rebuilds all prepared entries and coverage on the server thread. Failure publishes nothing.
     * Built-in mixins invoke this after successful datapack reload and at initial server ticking.
     * Explicit calls also support mods which mutate recipes programmatically outside /reload.
     *
     * @return new coherent generation and unsupported-id report
     */
    public RecipeReloadResult refresh() {
        checkOpen();
        usable = false;
        var source = server.getRecipeManager();
        var entries = new ObjectArrayList<RecipeEntry<?>>();
        var unsupported = new ObjectArrayList<String>();
        var registries = server.registryAccess();
        for (var nativeEntry : source.getRecipes()) {
            //? if >=1.20.2 {
            /*Recipe<?> recipe = nativeEntry.value();
             *///?} else {
            Recipe<?> recipe = nativeEntry;
            //?}
            //? if >=1.21.2 {
            /*String id = nativeEntry.id().identifier().toString();
             *///?} else if >=1.20.2 {
            /*String id = nativeEntry.id().toString();
             *///?} else {
            String id = recipe.getId().toString();
            //?}
            RecipeEntry<?> prepared = prepare(id, recipe, registries);
            if (prepared == null) {
                unsupported.add(id);
                continue;
            }
            if (!id.equals(prepared.id())) {
                throw new IllegalArgumentException("Prepared recipe adapter changed native id " + id);
            }
            entries.add(prepared);
        }
        var published = new RecipeReloadResult(manager.replaceAll(entries), unsupported);
        result = published;
        importedSource = source;
        failedSource = null;
        usable = true;
        return published;
    }

    /**
     * Imports a replaced native manager once before ECS ticks, with no recipe traversal on a hit.
     */
    @ApiStatus.Internal
    public void refreshIfChanged() {
        checkOpen();
        var source = server.getRecipeManager();
        if (importedSource == source || failedSource == source) {
            return;
        }
        try {
            refresh();
        } catch (RuntimeException failure) {
            failedSource = source;
            dev.sixik.sdmal.SDMAdvancedLibrary.LOGGER.error(
                    "Prepared recipes could not be rebuilt; previous generation retained for reads, application disabled", failure);
        }
    }

    /**
     * Invalidates current data at server stop while allowing already captured generations to finish.
     */
    @ApiStatus.Internal
    public void close() {
        closed = true;
        usable = false;
    }

    /**
     * Dispatches an exact-class adapter without permitting unsafe superclass fallback.
     *
     * @param id         complete native recipe id
     * @param recipe     native source on the owner thread
     * @param registries owner registry context
     * @return immutable prepared entry or null when unsupported
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private RecipeEntry<?> prepare(String id, Recipe<?> recipe, net.minecraft.core.RegistryAccess registries) {
        MinecraftRecipeAdapter adapter = adapters.get(recipe.getClass());
        return adapter == null ? null : adapter.prepare(id, recipe, registries);
    }

    /**
     * Checks owner affinity and permanent lifetime before any native data access.
     */
    private void checkOpen() {
        NativeRecipes.checkOwner(server);
        if (closed) {
            throw new IllegalStateException("Server recipe service is closed");
        }
    }
}
