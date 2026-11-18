package dev.sixik.sdmal.impl.mixin.recipes;

import dev.sixik.sdmal.api.recipes.minecraft.ServerRecipes;
import dev.sixik.sdmal.core.recipes.ServerRecipeAccess;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes immutable native recipe generations before ECS ticking and after successful /reload.
 */
@Mixin(MinecraftServer.class)
public abstract class Mixin$Recipes$MinecraftServer implements ServerRecipeAccess {
    /**
     * Exact-server service; no global server reference or cross-world static cache is used.
     */
    @Unique
    private final ServerRecipes sdmal$recipeManager = new ServerRecipes((MinecraftServer) (Object) this);

    /**
     * @return prepared recipe service for this server instance
     */
    @Override
    public ServerRecipes sdmal$recipes() { return sdmal$recipeManager; }

    /**
     * Initializes/refreshes a changed native manager before any world ECS jobs begin.
     *
     * @param callback native tick callback
     */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void sdmal$prepareRecipes(CallbackInfo callback) {
        sdmal$recipeManager.refreshIfChanged();
    }

    /**
     * Makes the reload future include owner-thread snapshot publication. Failed native reloads
     * leave prepared data unchanged, while adapter failures propagate through the returned future.
     *
     * @param callback native reload future
     */
    @Inject(method = "reloadResources", at = @At("RETURN"), cancellable = true)
    private void sdmal$reloadRecipes(CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        var server = (MinecraftServer) (Object) this;
        callback.setReturnValue(callback.getReturnValue().thenRunAsync(sdmal$recipeManager::refresh, server));
    }

    /**
     * Invalidates generation checks when the server stops without mutating outstanding snapshots.
     *
     * @param callback server shutdown callback
     */
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sdmal$stopRecipes(CallbackInfo callback) {
        sdmal$recipeManager.close();
    }
}
