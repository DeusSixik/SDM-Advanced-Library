package dev.sixik.sdmal.example.ecs.multiblock;

import dev.sixik.sdmal.example.ecs.blockentity.EcsExampleBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Placeable controller with shared right-click diagnostics and no vanilla block entity ticker.
 */
public final class MultiblockControllerBlock extends EcsExampleBlock {
    /**
     * Registered controller shell type, resolved only after the block/type registration cycle.
     */
    private final Supplier<? extends BlockEntityType<?>> type;

    /**
     * Creates the controller block.
     *
     * @param properties version-appropriate block properties
     * @param type supplier of its registered block entity type
     */
    public MultiblockControllerBlock(
            Properties properties, Supplier<? extends BlockEntityType<?>> type) {
        super(properties);
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Creates only the Minecraft shell; the world's ECS owns component dispatch.
     *
     * @param position controller coordinate
     * @param state controller state
     * @return new unbound shell
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new MultiblockControllerBlockEntity(type.get(), position, state);
    }

    /**
     * Reports formation and production after the shared interaction handler checks the server side.
     *
     * @param level server world
     * @param position clicked coordinate
     * @param player receiving player
     */
    @Override
    protected void showDebugInfo(Level level, BlockPos position, Player player) {
        if (level.getBlockEntity(position) instanceof MultiblockControllerBlockEntity controller) {
            controller.sendDebugInfo(player);
        }
    }
}
