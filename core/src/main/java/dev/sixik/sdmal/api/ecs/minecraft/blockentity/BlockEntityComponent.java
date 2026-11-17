package dev.sixik.sdmal.api.ecs.minecraft.blockentity;

import dev.sixik.sdmal.api.ecs.AbstractWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/**
 * Runtime position and weak host binding. Position is derived from the BlockEntity rather than
 * persisted twice. Methods belong to the world thread; capture immutable position/state before
 * entering worker computation.
 */
@EcsComponent(id = "sdmal:block_entity")
public final class BlockEntityComponent extends AbstractWorldObjectComponent
        implements BooleanSupplier {
    /**
     * Weak reference prevents manager values from strongly retaining their weak-key world through a
     * BlockEntity.
     */
    private WeakReference<EcsBlockEntity> host = new WeakReference<>(null);

    /**
     * Immutable Minecraft coordinate, replaced only when binding the host.
     */
    private BlockPos position = BlockPos.ZERO;

    /**
     * Creates an unbound descriptor instance for service discovery and archive restoration.
     */
    public BlockEntityComponent() {}

    /**
     * Binds runtime state after archive restoration and before manager publication.
     *
     * @param blockEntity exact host instance
     */
    void bind(EcsBlockEntity blockEntity) {
        host = new WeakReference<>(blockEntity);
        position = blockEntity.getBlockPos().immutable();
    }

    /**
     * @return immutable block coordinate, safe to include in an independent worker snapshot
     */
    public BlockPos position() {
        return position;
    }

    /**
     * Marks the owning chunk dirty after persisted ECS data changes on the world thread.
     *
     * @throws IllegalStateException if invoked during worker computation
     */
    public void setChanged() {
        owner().flatMap(object -> object.manager()).ifPresent(manager -> manager.checkThread());
        EcsBlockEntity entity = host.get();
        if (entity != null && !entity.isRemoved()) entity.setChanged();
    }

    /**
     * Requests the vanilla BlockEntity update packet after synchronized state changes. This is
     * explicit so a component can batch updates rather than sending a packet every tick.
     */
    public void requestClientUpdate() {
        owner().flatMap(object -> object.manager()).ifPresent(manager -> manager.checkThread());
        EcsBlockEntity entity = host.get();
        if (entity == null || entity.isRemoved()) return;
        Level level = entity.getLevel();
        if (level == null || level.isClientSide()) return;
        var state = entity.getBlockState();
        level.sendBlockUpdated(position, state, state, 2);
    }

    /**
     * Follows vanilla block ticking eligibility, including simulation distance and the world
     * border. Client mirrors receive synchronization but never run authoritative component
     * simulation.
     *
     * @return whether this exact installed server-side host may tick
     */
    @Override
    public boolean getAsBoolean() {
        owner().flatMap(object -> object.manager()).ifPresent(manager -> manager.checkThread());
        EcsBlockEntity entity = host.get();
        if (entity == null || entity.isRemoved()) return false;
        Level level = entity.getLevel();
        return level != null
                && !level.isClientSide()
                && level.hasChunkAt(position)
                && level.getWorldBorder().isWithinBounds(position)
                && level.shouldTickBlocksAt(position)
                && level.getBlockEntity(position) == entity;
    }
}
