package dev.sixik.sdmal.example.ecs.blockentity;

import dev.sixik.sdmal.api.ecs.WorldObject;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.EcsBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Example shell for a mod's registered machine block. The block's getTicker must return null.
 * Register the BlockEntityType through the consuming mod's loader registry; no additional ECS
 * registration is needed.
 */
public final class GeneratorBlockEntity extends EcsBlockEntity {
    /**
     * Creates a typed Minecraft shell without any machine-specific ticking logic.
     *
     * @param type     consuming mod's registered block entity type
     * @param position host coordinate
     * @param state    host block state
     */
    public GeneratorBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }

    /**
     * @param object new hosted composition; restoration uses saved types instead of this factory
     */
    @Override
    protected void createComponents(WorldObject object) {
        object.attach(
                new BlockGeneratorComponent(),
                WorldObjectComponentRegistry.instance().require(BlockGeneratorComponent.class));
    }

    /**
     * @param object newly bound or synchronized composition
     */
    @Override
    protected void onEcsReady(WorldObject object) {
        object.getSingle(BlockGeneratorComponent.class).orElseThrow().publishView();
    }

    /**
     * @return immutable renderer projection, or null while the chunk is unavailable
     */
    public BlockGeneratorComponent.View renderState() {
        return ecsObject()
                .flatMap(object -> object.getSingle(BlockGeneratorComponent.class))
                .map(BlockGeneratorComponent::state)
                .orElse(null);
    }

    /**
     * Reports current server state to one player without waiting for client synchronization.
     *
     * @param player player receiving the chat message
     */
    public void sendDebugInfo(Player player) {
        if (getLevel() == null || getLevel().isClientSide()) return;
        var object = ecsObject().orElse(null);
        var state = renderState();
        if (object == null || state == null) {
            player.sendSystemMessage(Component.translatable("message.sdmal.ecs_generator.inactive",
                    ecsRestoreFailure().orElse("ECS object is unavailable")));
            return;
        }
        long tick = object.manager().orElseThrow().lastTick();
        player.sendSystemMessage(Component.translatable("message.sdmal.ecs_generator.energy",
                state.energy(), state.capacity(), tick));
    }
}
