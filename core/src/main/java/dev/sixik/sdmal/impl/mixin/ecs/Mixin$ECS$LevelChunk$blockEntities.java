package dev.sixik.sdmal.impl.mixin.ecs;

import dev.sixik.sdmal.api.ecs.minecraft.blockentity.EcsBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Captures hosted ECS state before common chunk teardown, independent of loader-specific unload
 * callbacks.
 */
@Mixin(LevelChunk.class)
public abstract class Mixin$ECS$LevelChunk$blockEntities {
    /**
     * @return live chunk block entities, inspected before vanilla clears them
     */
    @Shadow
    public abstract Map<BlockPos, BlockEntity> getBlockEntities();

    /**
     * Releases exact hosted membership while preserving shell-owned data for a later chunk
     * save/reload.
     *
     * @param ci Mixin callback
     */
    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
    private void sdm$releaseHostedObjects(CallbackInfo ci) {
        for (BlockEntity entity : getBlockEntities().values()) {
            if (entity instanceof EcsBlockEntity hosted) hosted.releaseEcsObject();
        }
    }
}
