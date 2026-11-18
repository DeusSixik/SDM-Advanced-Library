package dev.sixik.sdmal.example.ecs.blockentity;

import dev.sixik.sdmal.api.ecs.SnapshotTickableComponent;
import dev.sixik.sdmal.api.ecs.EcsContext;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.BlockEntityComponent;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.RenderStateComponent;

/**
 * Chunk-hosted machine: worker calculation, owner-thread apply, dirty marking and batched client
 * updates.
 */
@EcsComponent(id = "sdmal_example:block_generator")
public final class BlockGeneratorComponent
        extends RenderStateComponent<BlockGeneratorComponent.View>
        implements SnapshotTickableComponent<Integer, Integer> {
    /**
     * Stored charge in [0,1000], persisted and selected for direct client synchronization.
     */
    @EcsPersist("energy")
    @EcsSync
    private int energy;

    /**
     * Creates an empty machine without requiring a world or BlockEntity during service discovery.
     */
    public BlockGeneratorComponent() {
    }

    /**
     * @param context owner-thread capture context
     * @return independent copied charge
     */
    @Override
    public Integer capture(EcsContext context) {
        return energy;
    }

    /**
     * @param snapshot independent copied charge
     * @param context  restricted worker context
     * @return next charge, capped at 1000 units
     */
    @Override
    public Integer compute(Integer snapshot, EcsContext context) {
        return Math.min(1000, snapshot + 1);
    }

    /**
     * Applies computation, marks chunk saving and sends updates once per 20 ticks or on reaching
     * capacity.
     *
     * @param result  independent computed charge
     * @param context owner-thread commit context
     */
    @Override
    public void apply(Integer result, EcsContext context) {
        if (energy == result) return;
        energy = result;
        publishView();
        BlockEntityComponent host =
                owner().orElseThrow().getSingle(BlockEntityComponent.class).orElseThrow();
        host.setChanged();
        if (context.tick() % 20 == 0 || energy == 1000) host.requestClientUpdate();
    }

    /**
     * Rebuilds the renderer's immutable projection after disk/network load or a simulation commit.
     */
    public void publishView() {
        publishState(new View(energy, 1000));
    }

    /**
     * Renderer input containing no mutable ECS/world references.
     *
     * @param energy   stored charge
     * @param capacity maximum charge
     */
    public record View(int energy, int capacity) {
    }
}
