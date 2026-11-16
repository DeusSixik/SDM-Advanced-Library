package dev.sixik.sdmal.impl.mixin.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.minecraft.LevelObjectManager;
import dev.sixik.sdmal.impl.ecs.WorldObjectTicker;
import dev.sixik.sdmal.impl.ecs.WorldObjectFileStorage;
import java.io.IOException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ticks the ECS manager once after each server-world tick. */
@Mixin(ServerLevel.class)
public final class Mixin$ECS$ServerLevel$tick {

    /** World-specific file session; a failed load disables automatic replacement. */
    @Unique private WorldObjectFileStorage sdm$storage;

    /**
     * Restores ECS after ServerLevel has finished initializing its server/dimension references.
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void sdm$load(CallbackInfo ci) {
        if (sdm$storage != null) return;
        ServerLevel level = (ServerLevel) (Object) this;
        //? if >=26.1 {
        /*var dimension = level.dimension().identifier();
        *///?} else {
        var dimension = level.dimension().location();
        //?}
        sdm$storage = new WorldObjectFileStorage(WorldObjectFileStorage.pathFor(
            level.getServer().getWorldPath(LevelResource.ROOT), dimension.getNamespace(), dimension.getPath()));
        try {
            var warnings = sdm$storage.load(LevelObjectManager.getManager(level));
            for (String warning : warnings) SDMAdvancedLibrary.LOGGER.warn("ECS archive: {}", warning);
        } catch (IOException failure) {
            SDMAdvancedLibrary.LOGGER.error("ECS archive could not be loaded; automatic saving is disabled for this world", failure);
        }
    }

    /**
     * Saves ECS with a vanilla dimension save, respecting save-off/noSave.
     * @param progress optional vanilla progress reporter
     * @param flush whether vanilla flushes its storage
     * @param noSave true when vanilla should skip saving this dimension
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "save", at = @At("HEAD"))
    private void sdm$save(ProgressListener progress, boolean flush, boolean noSave, CallbackInfo ci) {
        if (!noSave) sdm$saveArchive();
    }

    /**
     * Captures final state before Level.close unregisters and detaches the manager.
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void sdm$saveBeforeClose(CallbackInfo ci) {
        if (!((ServerLevel) (Object) this).noSave) sdm$saveArchive();
    }

    /** Records save errors without replacing a previous successful archive. */
    @Unique private void sdm$saveArchive() {
        if (sdm$storage == null || !sdm$storage.isWritable()) return;
        WorldObjectManager manager = LevelObjectManager.getManager((Level) (Object) this);
        if (manager.isClosed()) return;
        try { sdm$storage.save(manager); }
        catch (IOException | dev.sixik.sdmal.core.ecs.WorldObjectException failure) {
            SDMAdvancedLibrary.LOGGER.error("ECS archive save failed; previous archive retained", failure);
        }
    }

    /**
     * Runs ECS objects after vanilla has processed this server world.
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
}
