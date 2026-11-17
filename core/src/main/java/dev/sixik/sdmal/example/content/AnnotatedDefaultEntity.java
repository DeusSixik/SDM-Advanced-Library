package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minimal native entity: its model-rendered block and block item are generated automatically.
 */
@RegisterContent(
        id = "sdmal:annotated_default_machine",
        tab = "sdmal:annotated_content",
        ordinal = 2)
public final class AnnotatedDefaultEntity extends BlockEntity {
    /**
     * @param type     injected registered native type
     * @param position host coordinate
     * @param state    current host state
     */
    public AnnotatedDefaultEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }
}
