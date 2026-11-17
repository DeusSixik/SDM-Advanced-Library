package dev.sixik.sdmal.impl.mixin.client.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.ecs.WorldObjectException;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import dev.sixik.sdmal.api.ecs.minecraft.LevelObjectManager;
import dev.sixik.sdmal.api.ecs.minecraft.WorldObjectManagerApi;
import dev.sixik.sdmal.impl.ecs.WorldObjectTicker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spans ECS phases across the client's separate entity and world update calls. */
@Mixin(ClientLevel.class)
public final class Mixin$ECS$ClientLevel$tick {

    /**
     * Opens PRE at the beginning of client entity processing, before ClientLevel.tick.
     * @param ci Mixin callback state
     */
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void sdm$preTick(CallbackInfo ci) {
        Level level = (Level) (Object) this;
        boolean running = true;
        //? if >=1.20.3
        //running = level.tickRateManager().runsNormally();
        WorldObjectTicker.begin(LevelObjectManager.getManager(level), running);
    }

    /**
     * Runs NORMAL after client entities and before the same method dispatches block entities.
     * @param ci Mixin callback state
     */
    //? if <26.1 {
    @Inject(method = "tickEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V"))
    private void sdm$normalTick(CallbackInfo ci) {
        WorldObjectTicker.normal(LevelObjectManager.getManager((Level) (Object) this));
    }
    //?}

    /**
     * Runs ECS objects after vanilla has processed this client world.
     *
     * <p>Failures are logged after {@link WorldObjectManager} has attempted every
     * object. A faulty ECS component therefore does not abort the Minecraft tick.
     *
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void sdm$tick(CallbackInfo ci) {
        Level level = (Level) (Object) this;
        boolean running = true;
        //? if >=1.20.3
        //running = level.tickRateManager().runsNormally();
        WorldObjectTicker.tick(LevelObjectManager.getManager(level), running);
    }

    /**
     * Releases this client's registered manager when its connection is closed.
     * Arguments of ClientLevel.disconnect vary between game versions, so the
     * callback accepts only the common callback state.
     *
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "disconnect", at = @At("TAIL"))
    private void sdm$disconnect(CallbackInfo ci) {
        try {
            WorldObjectManagerApi.unregisterWorld((Level) (Object) this);
        } catch (WorldObjectException failure) {
            SDMAdvancedLibrary.LOGGER.error("ECS client-world cleanup failed", failure);
        }
    }
}
