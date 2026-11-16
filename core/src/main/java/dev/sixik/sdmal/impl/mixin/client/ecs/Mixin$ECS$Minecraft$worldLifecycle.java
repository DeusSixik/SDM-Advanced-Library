package dev.sixik.sdmal.impl.mixin.client.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.core.ecs.WorldObjectException;
import dev.sixik.sdmal.core.ecs.minecraft.WorldObjectManagerApi;
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
