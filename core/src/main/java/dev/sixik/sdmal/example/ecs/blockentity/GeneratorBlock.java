package dev.sixik.sdmal.example.ecs.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Example machine block. Register it and its BlockEntityType with the consuming mod's loader.
 * EntityBlock's default null ticker keeps all simulation in the world's ECS scheduler.
 */
public final class GeneratorBlock extends EcsExampleBlock {
    /**
     * Deferred type lookup resolves the block/type registry initialization cycle.
     */
    private final Supplier<? extends BlockEntityType<?>> type;

    /**
     * Creates a machine block without a vanilla ticker.
     *
     * @param properties normal Minecraft block properties
     * @param type supplier of the consuming mod's registered block entity type
     */
    public GeneratorBlock(Properties properties, Supplier<? extends BlockEntityType<?>> type) {
        super(properties);
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Creates the thin Minecraft shell; ECS components are initialized after world assignment.
     *
     * @param position host coordinate
     * @param state host block state
     * @return new unbound shell
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new GeneratorBlockEntity(type.get(), position, state);
    }

    /**
     * Reports the standalone generator's server-side energy.
     *
     * @param level server world
     * @param position clicked coordinate
     * @param player receiving player
     */
    @Override
    protected void showDebugInfo(Level level, BlockPos position, Player player) {
        if (level.getBlockEntity(position) instanceof GeneratorBlockEntity generator) {
            generator.sendDebugInfo(player);
        }
    }
}
