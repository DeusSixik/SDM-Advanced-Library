package dev.sixik.sdmal.impl.mixin.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.ecs.EcsObjectException;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.ecs.minecraft.LevelEcsAccess;
import dev.sixik.sdmal.api.ecs.minecraft.LevelEcsManagers;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates each Minecraft world with its own ECS manager. */
@Mixin(Level.class)
public class Mixin$ECS$Level$objectManager implements LevelEcsAccess {

    /** Manager registered after construction; null before the constructor callback. */
    @Unique
    private EcsObjectManager sdm$manager;

    /**
     * Registers this world after the base constructor has initialized it.
     * @param ci callback state supplied by Mixin
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    public void sdm$init(CallbackInfo ci) {
        sdm$manager = LevelEcsManagers.registerWorld((Level) (Object) this);

        SDMAdvancedLibrary.LOGGER.info("World object manager initialized");
    }

    /**
     * Returns the manager registered for this world instance.
     * @return manager, non-null after the constructor callback
     */
    @Override
    public EcsObjectManager getManager() {
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
            LevelEcsManagers.unregisterWorld((Level) (Object) this);
        } catch (EcsObjectException failure) {
            SDMAdvancedLibrary.LOGGER.error("ECS world cleanup failed", failure);
        }
    }
}
