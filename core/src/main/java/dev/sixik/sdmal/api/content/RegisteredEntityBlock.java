package dev.sixik.sdmal.api.content;

import dev.sixik.sdmal.core.content.ContentRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Default model-rendered block for an annotated BlockEntity, with no vanilla ticker. Extend and
 * annotate this class with the entity's id to customize block behavior without repeating its
 * factory.
 */
public class RegisteredEntityBlock extends Block implements EntityBlock {
    /**
     * @param properties injected keyed block properties; customize them before calling super
     */
    public RegisteredEntityBlock(Properties properties) {
        super(properties);
    }

    /**
     * @param position placed or loaded block coordinate
     * @param state    current block state
     * @return new instance of the annotated block entity sharing this block's id
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return ContentRuntime.registration().newBlockEntity(this, position, state);
    }
}
