package dev.sixik.sdmal.example.ecs.multiblock;

import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.EcsBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One chunk-owned shell for the whole example structure. Iron supports and the redstone core are
 * ordinary blocks.
 */
public final class MultiblockControllerBlockEntity extends EcsBlockEntity {
    /**
     * Creates the registered controller shell without accessing a world in its constructor.
     *
     * @param type registered type accepting the controller block
     * @param position controller coordinate
     * @param state controller state
     */
    public MultiblockControllerBlockEntity(
            BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }

    /**
     * Creates the default controller composition; disk restoration uses the saved registered types.
     *
     * @param object new object with its runtime BlockEntityComponent already attached
     */
    @Override
    protected void createComponents(EcsObject object) {
        object.attach(new MultiblockGeneratorComponent());
    }

    /**
     * Rebuilds immutable render/debug state after disk load or client composition replacement.
     *
     * @param object newly bound composition
     */
    @Override
    protected void onEcsReady(EcsObject object) {
        object.getSingle(MultiblockGeneratorComponent.class).orElseThrow().publishView();
    }

    /**
     * @return latest immutable client/server projection, or null before activation
     */
    public MultiblockGeneratorComponent.View renderState() {
        return ecsObject()
                .flatMap(object -> object.getSingle(MultiblockGeneratorComponent.class))
                .map(MultiblockGeneratorComponent::state)
                .orElse(null);
    }

    /**
     * Builds the example structure in empty positions on the server and reports the outcome.
     * Existing energy and controller identity are preserved; ECS checks formation on its next tick.
     *
     * @param player player requesting assembly through the example item's sneaking interaction
     */
    public void assemble(Player player) {
        var level = getLevel();
        if (level == null || level.isClientSide()) return;
        ecsObject().flatMap(EcsObject::manager).ifPresent(manager -> manager.checkThread());
        var result = MultiblockAssembler.assemble(level, this, player);
        player.sendSystemMessage(Component.translatable(result.messageKey()));
    }

    /**
     * Sends server-authoritative formation, energy and first-missing-part diagnostics to one
     * player.
     *
     * @param player recipient of the chat messages
     */
    public void sendDebugInfo(Player player) {
        if (getLevel() == null || getLevel().isClientSide()) return;
        var object = ecsObject().orElse(null);
        var view = renderState();
        if (object == null || view == null) {
            player.sendSystemMessage(
                    Component.translatable(
                            "message.sdmal.ecs_multiblock.inactive",
                            ecsRestoreFailure().orElse("ECS object is unavailable")));
            return;
        }
        String statusKey =
                switch (view.status()) {
                    case CHECKING -> "checking";
                    case FORMED -> "formed";
                    case INCOMPLETE -> "incomplete";
                    case UNLOADED -> "unloaded";
                    case OUT_OF_BOUNDS -> "out_of_bounds";
                };
        player.sendSystemMessage(
                Component.translatable(
                        "message.sdmal.ecs_multiblock.status",
                        Component.translatable("message.sdmal.ecs_multiblock." + statusKey),
                        view.energy(),
                        MultiblockGeneratorComponent.CAPACITY,
                        view.matchedParts(),
                        MultiblockPattern.PARTS,
                        object.manager().orElseThrow().lastTick()));
        int missing = view.missingPart();
        if (view.status() != MultiblockStatus.INCOMPLETE || missing < 0) return;
        var position = new BlockPos.MutableBlockPos();
        MultiblockPattern.position(getBlockPos(), missing, position);
        var expected =
                Component.translatable(
                        missing == MultiblockPattern.PARTS - 1
                                ? "block.minecraft.redstone_block"
                                : "block.minecraft.iron_block");
        player.sendSystemMessage(
                Component.translatable(
                        "message.sdmal.ecs_multiblock.missing",
                        expected,
                        position.getX(),
                        position.getY(),
                        position.getZ()));
    }
}
