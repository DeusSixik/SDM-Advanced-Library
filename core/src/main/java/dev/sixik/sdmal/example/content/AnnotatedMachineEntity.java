package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.EcsBlockEntity;
import dev.sixik.sdmal.example.ecs.blockentity.BlockGeneratorComponent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ECS shell whose native block/type/item are registered from annotations, with no static registry
 * fields.
 */
@RegisterContent(id = "sdmal:annotated_machine", tab = "sdmal:annotated_content", ordinal = 1)
public final class AnnotatedMachineEntity extends EcsBlockEntity {
    /**
     * @param type     injected native registered type
     * @param position host coordinate
     * @param state    host
     *                 block state
     */
    public AnnotatedMachineEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }

    /**
     * @param object new ECS composition after the world has been assigned
     */
    @Override
    protected void createComponents(EcsObject object) {
        object.attach(new BlockGeneratorComponent());
    }
}
