package dev.sixik.sdmal.impl.mixin.client.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.ecs.WorldObjectException;
import dev.sixik.sdmal.api.ecs.minecraft.LevelObjectManager;
import dev.sixik.sdmal.api.ecs.minecraft.WorldObjectManagerApi;
import dev.sixik.sdmal.impl.ecs.WorldObjectTicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases replaced client dimensions and clears the last world when the client disconnects. */
@Mixin(Minecraft.class)
public class Mixin$ECS$Minecraft$worldLifecycle {
    /** Last world installed into client engines; null before joining or after unload. */
    @Unique private ClientLevel sdm$installedWorld;

    /**
     * Runs NORMAL at the modern client's relocated block-entity boundary.
     * Since 26.1 Minecraft.tick dispatches block entities separately from ClientLevel.tickEntities.
     * @param ci Mixin callback state
     */
    //? if >=26.1 {
    /*@Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V"))
    private void sdm$normalTick(CallbackInfo ci) {
        ClientLevel level = ((Minecraft) (Object) this).level;
        if (level != null) WorldObjectTicker.normal(LevelObjectManager.getManager(level));
    }
    *///?}

    /**
     * Releases the previous world's manager before client engines adopt another world.
     * The two-argument overload is the shared implementation in modern Minecraft.
     * @param nextWorld new client world, or null while leaving a world
     * @param ci callback state supplied by Mixin
     */
    //? if >=26.1 {
    /*@Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    private void sdm$replaceWorld(ClientLevel nextWorld, boolean stopSound, CallbackInfo ci) {
    *///?} else {
    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"))
    private void sdm$replaceWorld(ClientLevel nextWorld, CallbackInfo ci) {
    //?}
        ClientLevel previous = sdm$installedWorld;
        sdm$installedWorld = nextWorld;
        if (previous == null || previous == nextWorld) return;
        try {
            WorldObjectManagerApi.unregisterWorld(previous);
        } catch (WorldObjectException failure) {
            SDMAdvancedLibrary.LOGGER.error("ECS replaced client-world cleanup failed", failure);
        }
    }
}
