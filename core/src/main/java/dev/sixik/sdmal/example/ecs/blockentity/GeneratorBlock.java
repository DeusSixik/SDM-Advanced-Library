package dev.sixik.sdmal.example.ecs.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
//? if >=1.21 && <1.21.2
//import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Example machine block. Register it and its BlockEntityType with the consuming mod's loader.
 * EntityBlock's default null ticker keeps all simulation in the world's ECS scheduler.
 */
public final class GeneratorBlock extends Block implements EntityBlock {
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
     * Sends authoritative ECS energy to the clicking player once, from the server world.
     * @param level world receiving the interaction
     * @param position clicked block coordinate
     * @param player interacting player
     */
    private void showDebugInfo(Level level, BlockPos position, Player player) {
        if (level.isClientSide()) return;
        if (level.getBlockEntity(position) instanceof GeneratorBlockEntity generator) {
            generator.sendDebugInfo(player);
        }
    }

    /**
     * Handles a right click and prints the server's current energy.
     * @param state clicked state
     * @param level owning world
     * @param position clicked coordinate
     * @param player interacting player
     * @param hit ray trace result
     * @return success to consume the interaction
     */
    //? if >=1.21 {
    /*@Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
            Player player, BlockHitResult hit) {
        showDebugInfo(level, position, player);
        return InteractionResult.SUCCESS;
    }
    *///?} else {
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos position,
            Player player, InteractionHand hand, BlockHitResult hit) {
        showDebugInfo(level, position, player);
        return InteractionResult.SUCCESS;
    }
    //?}

    /**
     * Handles item-in-hand interaction without using or consuming the held item.
     * @param stack held item
     * @param state clicked state
     * @param level owning world
     * @param position clicked coordinate
     * @param player interacting player
     * @param hand interacting hand
     * @param hit ray trace result
     * @return success to consume the interaction
     */
    //? if >=1.21.2 {
    /*@Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos position, Player player, InteractionHand hand, BlockHitResult hit) {
        showDebugInfo(level, position, player);
        return InteractionResult.SUCCESS;
    }
    *///?} else if >=1.21 {
    /*@Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos position, Player player, InteractionHand hand, BlockHitResult hit) {
        showDebugInfo(level, position, player);
        return ItemInteractionResult.SUCCESS;
    }
    *///?}
}
