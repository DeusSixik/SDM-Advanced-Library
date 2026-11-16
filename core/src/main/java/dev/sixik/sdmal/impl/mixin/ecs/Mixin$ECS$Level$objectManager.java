package dev.sixik.sdmal.impl.mixin.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.core.ecs.WorldObjectException;
import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.minecraft.LevelObjectManager;
import dev.sixik.sdmal.core.ecs.minecraft.WorldObjectManagerApi;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates each Minecraft world with its own ECS manager. */
@Mixin(Level.class)
public class Mixin$ECS$Level$objectManager implements LevelObjectManager {

    /** Manager registered after construction; null before the constructor callback. */
    @Unique
    private WorldObjectManager sdm$manager;

    /**
     * Registers this world after the base constructor has initialized it.
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    public void sdm$init(CallbackInfo ci) {
        sdm$manager = WorldObjectManagerApi.registerWorld((Level) (Object) this);

        SDMAdvancedLibrary.LOGGER.info("World object manager initialized");
    }

    /**
     * Returns the manager registered for this world instance.
     * @return manager, non-null after the constructor callback
     */
    @Override
    public WorldObjectManager getManager() {
        return sdm$manager;
    }

    /**
     * Unregisters this world and reports lifecycle failures after cleanup.
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "close", at = @At("HEAD"))
    public void sdm$close(CallbackInfo ci) {
        if (sdm$manager == null) {
            return;
        }

        try {
            WorldObjectManagerApi.unregisterWorld((Level) (Object) this);
        } catch (WorldObjectException failure) {
            SDMAdvancedLibrary.LOGGER.error("ECS world cleanup failed", failure);
        }
    }
}
