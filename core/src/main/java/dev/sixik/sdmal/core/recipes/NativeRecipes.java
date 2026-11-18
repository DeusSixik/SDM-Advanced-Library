package dev.sixik.sdmal.core.recipes;

import com.mojang.serialization.DynamicOps;
import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeType;
import dev.sixik.sdmal.api.recipes.minecraft.CookingRecipe;
import dev.sixik.sdmal.api.recipes.minecraft.FrozenItemStack;
import dev.sixik.sdmal.api.recipes.minecraft.SingleItemInput;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.ApiStatus;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}

/**
 * Owner-thread native version bridges used while preparing immutable recipe generations.
 */
@ApiStatus.Internal
public final class NativeRecipes {
    /**
     * Optional loader extension exposing a wrapped custom ingredient; resolved once at startup.
     */
    private static final java.lang.reflect.Method CUSTOM_INGREDIENT = customIngredientMethod();

    /**
     * Prevents construction.
     */
    private NativeRecipes() {
    }

    /**
     * @param item native registry item
     * @return numeric item id, stable during this server lifetime
     */
    public static int itemId(Item item) {
        //? if >=1.19.3 {
        return BuiltInRegistries.ITEM.getId(item);
        //?} else {
        /*return Registry.ITEM.getId(item);
         *///?}
    }

    /**
     * @param registries owner-thread registry access
     * @return version-appropriate stack serialization context
     */
    public static DynamicOps<Tag> stackOps(RegistryAccess registries) {
        //? if >=1.20.5 {
        /*return registries.createSerializationContext(NbtOps.INSTANCE);
         *///?} else {
        return NbtOps.INSTANCE;
        //?}
    }

    /**
     * Requires the native server owner before reading/reconstructing mutable Minecraft objects.
     *
     * @param server owning native server
     * @throws IllegalStateException on worker threads
     */
    public static void checkOwner(MinecraftServer server) {
        java.util.Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Prepare/apply native recipes on the server thread; query captured snapshots on workers");
        }
    }

    /**
     * Freezes a known vanilla recipe and a plain vanilla ingredient. Unknown/custom ingredients
     * are reported as unsupported rather than approximated by display items.
     *
     * @param id           complete recipe id
     * @param nativeRecipe exact vanilla cooking implementation
     * @param type         shared prepared category token
     * @param registries   owner-thread registries
     * @return detached recipe, or null for an unsupported ingredient
     */
    public static RecipeEntry<CookingRecipe> cooking(String id, AbstractCookingRecipe nativeRecipe,
                                                     RecipeType<SingleItemInput, CookingRecipe> type, RegistryAccess registries) {
        //? if >=1.21.2 {
        /*Ingredient ingredient = nativeRecipe.input();
         *///?} else {
        Ingredient ingredient = nativeRecipe.getIngredients().get(0);
        //?}
        if (!vanillaIngredient(ingredient)) {
            return null;
        }
        IntArrayList items = new IntArrayList();
        //? if >=1.21.2 {
        /*ingredient.items().forEach(holder -> items.add(itemId(holder.value())));
        var result = nativeRecipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(
                net.minecraft.world.item.ItemStack.EMPTY));
        int ticks = nativeRecipe.cookingTime();
        float experience = nativeRecipe.experience();
        *///?} else if >=1.19.3 {
        for (var stack : ingredient.getItems()) items.add(itemId(stack.getItem()));
        var result = nativeRecipe.getResultItem(registries);
        int ticks = nativeRecipe.getCookingTime();
        float experience = nativeRecipe.getExperience();
        //?} else {
        /*for (var stack : ingredient.getItems()) items.add(itemId(stack.getItem()));
        var result = nativeRecipe.getResultItem();
        int ticks = nativeRecipe.getCookingTime();
        float experience = nativeRecipe.getExperience();
        *///?}
        return new RecipeEntry<>(id, type, new CookingRecipe(items.toIntArray(),
                new FrozenItemStack(result, registries), ticks, experience));
    }

    /**
     * Accepts only a vanilla concrete ingredient with no loader-wrapped custom implementation.
     *
     * @param ingredient owner-thread native ingredient
     * @return true when item-id expansion preserves exact vanilla matching semantics
     */
    private static boolean vanillaIngredient(Ingredient ingredient) {
        if (ingredient.getClass() != Ingredient.class) {
            return false;
        }
        if (CUSTOM_INGREDIENT == null) {
            return true;
        }
        try {
            return CUSTOM_INGREDIENT.invoke(ingredient) == null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect loader ingredient extension", failure);
        }
    }

    /**
     * Finds the shared Fabric/NeoForge extension without linking loader classes into common code.
     * Older Forge subclasses are rejected by exact vanilla class checking instead.
     *
     * @return optional custom ingredient getter, or null in unextended vanilla/older Forge
     */
    private static java.lang.reflect.Method customIngredientMethod() {
        try {
            return Ingredient.class.getMethod("getCustomIngredient");
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
