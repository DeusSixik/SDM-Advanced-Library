package dev.sixik.sdmal.api.ecs.minecraft.blockentity;

import dev.sixik.sdmal.api.ecs.HostedWorldObject;
import dev.sixik.sdmal.api.ecs.WorldObject;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.minecraft.WorldObjectManagerApi;

import java.util.Optional;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21
//import net.minecraft.core.HolderLookup;
//? if >=1.21.5 {
/*import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
*///?}

/**
 * Thin Minecraft shell: chunk-owned persistence and lifecycle, world-driven ECS simulation, no vanilla ticker.
 * Subclasses supply new components only; restored component types come from their service registrations.
 */
public abstract class EcsBlockEntity extends BlockEntity {
    /**
     * Stable custom BlockEntity NBT key; component archives do not appear in the world's separate ECS file.
     */
    public static final String ECS_DATA_KEY = "sdmal_ecs";
    /**
     * Single-owner lifecycle/persistence handle, created without invoking subclass methods in the constructor.
     */
    private final HostedWorldObject ecs = new HostedWorldObject();
    /**
     * Initial chunk synchronization can arrive before vanilla assigns the client world.
     */
    private byte[] pendingClientSnapshot;

    /**
     * Creates a shell. Its BlockEntityType must be registered by the consuming mod as usual.
     *
     * @param type     registered type accepting the host block
     * @param position immutable block coordinate
     * @param state    host block state
     */
    protected EcsBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }

    /**
     * Creates the typed default composition for a new host; never called while restoring existing disk data.
     *
     * @param object new transient object with its BlockEntityComponent already attached
     */
    protected abstract void createComponents(WorldObject object);

    /**
     * Called after binding/restoration to rebuild render projections or cache component handles.
     *
     * @param object active composition; do not register another ticker
     */
    protected void onEcsReady(WorldObject object) {
    }

    /**
     * @return current live composition, or empty before installation, while unloaded or for unresolved data
     */
    public final Optional<WorldObject> ecsObject() {
        return ecs.object();
    }

    /**
     * @return restore diagnostic when saved component types or their data are currently unavailable
     */
    public final Optional<String> ecsRestoreFailure() {
        return ecs.restoreFailure();
    }

    /**
     * Associates the shell with a world; conditional activation rejects an instance not yet installed in its chunk.
     *
     * @param level owning Minecraft world
     */
    @Override
    public void setLevel(Level level) {
        if (getLevel() != null && getLevel() != level) releaseEcsObject();
        super.setLevel(level);
        if (!isRemoved()) {
            activateEcsObject();
            applyPendingClientSnapshot();
        }
    }

    /**
     * Restores membership when Minecraft reuses a removed shell during chunk installation.
     */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (getLevel() != null) activateEcsObject();
    }

    /**
     * Captures ECS data before exact-instance removal; pending results/commands become stale.
     */
    @Override
    public void setRemoved() {
        try {
            releaseEcsObject();
        } finally {
            super.setRemoved();
        }
    }

    /**
     * Releases chunk membership even on loaders whose unload path uses onChunkUnloaded instead of setRemoved.
     * Called by the common chunk lifecycle mixin; repeated calls are harmless.
     */
    public final void releaseEcsObject() {
        ecs.unbind();
    }

    /**
     * Binds typed components and reconstructs the runtime-only position/activation association.
     */
    private void activateEcsObject() {
        if (ecs.object().isPresent()) return;
        boolean active = ecs.bind(WorldObjectManagerApi.getManager(getLevel()), object -> {
            object.attach(new BlockEntityComponent(), WorldObjectComponentRegistry.instance().require(BlockEntityComponent.class));
            createComponents(object);
        }, this::bindRuntime);
        if (active) onEcsReady(ecs.object().orElseThrow());
    }

    /**
     * Reconstructs exact host identity and runtime position without persisting a Level reference.
     *
     * @param object unpublished restored/new client or server composition
     */
    private void bindRuntime(WorldObject object) {
        BlockEntityComponent binding = object.getSingle(BlockEntityComponent.class)
                .orElseThrow(() -> new IllegalStateException("Hosted object has no BlockEntityComponent"));
        binding.bind(this);
        object.setTickCondition(binding);
    }

    /**
     * Writes the direct ECS binary frame for a consuming mod's own loader packet transport.
     *
     * @param buffer destination buffer, not released by the shell
     */
    public final void writeClientSnapshot(FriendlyByteBuf buffer) {
        ecs.writeNetwork(buffer);
    }

    /**
     * Installs a complete client mirror on its world thread and rebuilds immutable rendering state.
     *
     * @param buffer direct ECS frame, not released by the shell
     */
    public final void readClientSnapshot(FriendlyByteBuf buffer) {
        if (getLevel() == null || !getLevel().isClientSide())
            throw new IllegalStateException("Client snapshot requires a client world");
        if (isRemoved()) throw new IllegalStateException("Cannot synchronize a removed BlockEntity");
        activateEcsObject();
        ecs.readNetwork(buffer, this::bindRuntime);
        onEcsReady(ecs.object().orElseThrow());
    }

    /**
     * Encodes only synchronized fields, never the host's disk archive or private persisted fields.
     * Vanilla transports a binary byte array inside its required BlockEntity update-tag envelope.
     *
     * @return vanilla-compatible envelope with a direct ECS frame
     */
    private CompoundTag clientUpdateTag() {
        CompoundTag tag = new CompoundTag();
        if (ecs.object().isEmpty()) return tag;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writeClientSnapshot(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            CompoundTag data = new CompoundTag();
            data.putByteArray("sync", bytes);
            tag.put(ECS_DATA_KEY, data);
            return tag;
        } finally {
            buffer.release();
        }
    }

    /**
     * @return vanilla update packet containing selected binary ECS data
     */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    //? if >=1.21 {
    /*@Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        return clientUpdateTag();
    }
    *///?} else {
    @Override
    public CompoundTag getUpdateTag() {
        return clientUpdateTag();
    }
    //?}

    /**
     * Applies a pre-world chunk packet after setLevel has installed the client binding.
     */
    private void applyPendingClientSnapshot() {
        if (pendingClientSnapshot == null || getLevel() == null) return;
        byte[] pending = pendingClientSnapshot;
        pendingClientSnapshot = null;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(pending));
        try {
            readClientSnapshot(buffer);
        } finally {
            buffer.release();
        }
    }

    /**
     * Replaces disk state without re-running new-object defaults for saved data.
     *
     * @param tag independently supplied BlockEntity payload
     */
    private void loadEcs(CompoundTag tag) {
        if (tag.get("sync") instanceof ByteArrayTag sync) {
            byte[] bytes = sync.getAsByteArray();
            if (bytes.length > HostedWorldObject.MAX_SNAPSHOT_BYTES)
                throw new IllegalArgumentException("Hosted snapshot exceeds 8 MiB");
            pendingClientSnapshot = bytes.clone();
            applyPendingClientSnapshot();
            return;
        }
        pendingClientSnapshot = null;
        releaseEcsObject();
        ecs.load(tag);
        if (getLevel() != null && !isRemoved()) activateEcsObject();
    }

    //? if >=1.21.5 {
    /*@Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        loadEcs(input.read(ECS_DATA_KEY, CompoundTag.CODEC).orElseGet(CompoundTag::new));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        CompoundTag data = ecs.save();
        if (!data.isEmpty()) output.store(ECS_DATA_KEY, CompoundTag.CODEC, data);
    }
    *///?} else if >=1.21 {
    /*@Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        loadEcs(tag.get(ECS_DATA_KEY) instanceof CompoundTag data ? data : new CompoundTag());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup);
        CompoundTag data = ecs.save();
        if (!data.isEmpty()) tag.put(ECS_DATA_KEY, data);
    }
    *///?} else {
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        loadEcs(tag.get(ECS_DATA_KEY) instanceof CompoundTag data ? data : new CompoundTag());
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag data = ecs.save();
        if (!data.isEmpty()) tag.put(ECS_DATA_KEY, data);
    }
    //?}
}
