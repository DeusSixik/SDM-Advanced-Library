package dev.sixik.sdmal.example.ecs.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
//? if >=1.21 && <1.21.2
//import net.minecraft.world.ItemInteractionResult;

/**
 * Shared version-specific right-click handling for the playable ECS example blocks.
 * EntityBlock's default null ticker keeps simulation in the world's ECS scheduler.
 */
public abstract class EcsExampleBlock extends Block implements EntityBlock {
    /**
     * Creates an example block with ordinary Minecraft block properties.
     * @param properties version-appropriate registry and physical properties
     */
    protected EcsExampleBlock(Properties properties) {
        super(properties);
    }

    /**
     * Sends authoritative ECS energy to the clicking player once, from the server world.
     * @param level world receiving the interaction
     * @param position clicked block coordinate
     * @param player interacting player
     */
    private void inspect(Level level, BlockPos position, Player player) {
        if (level.isClientSide()) return;
        showDebugInfo(level, position, player);
    }

    /**
     * Reports example-specific state after the shared handler has checked the server side.
     * @param level server world
     * @param position clicked coordinate
     * @param player receiving player
     */
    protected abstract void showDebugInfo(Level level, BlockPos position, Player player);

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
        inspect(level, position, player);
        return InteractionResult.SUCCESS;
    }
    *///?} else {
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos position,
            Player player, InteractionHand hand, BlockHitResult hit) {
        inspect(level, position, player);
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
        inspect(level, position, player);
        return InteractionResult.SUCCESS;
    }
    *///?} else if >=1.21 {
    /*@Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos position, Player player, InteractionHand hand, BlockHitResult hit) {
        inspect(level, position, player);
        return ItemInteractionResult.SUCCESS;
    }
    *///?}
}
