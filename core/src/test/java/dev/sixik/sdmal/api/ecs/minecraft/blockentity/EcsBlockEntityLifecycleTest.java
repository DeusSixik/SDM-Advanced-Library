package dev.sixik.sdmal.api.ecs.minecraft.blockentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.minecraft.WorldObjectManagerApi;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.border.WorldBorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs real shells, components and managers against a controlled Minecraft world boundary.
 */
class EcsBlockEntityLifecycleTest {
    /**
     * Fixture worlds retained until their managers have been closed.
     */
    private final List<Level> worlds = new ArrayList<>();

    /**
     * Exact coordinate used by the test shell.
     */
    private static final BlockPos POS = new BlockPos(3, 12, 9);

    /**
     * Initializes actual vanilla registries and component service discovery.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldObjectComponentRegistry.instance().registerServices();
    }

    /**
     * Releases manager registrations even after an assertion failure.
     */
    @AfterEach
    void cleanup() {
        worlds.forEach(WorldObjectManagerApi::unregisterWorld);
    }

    /**
     * Installation, chunk activity and exact host identity control the actual component simulation.
     */
    @Test
    void serverShell_chunkActivityAndIdentity_controlsWorldDrivenTicks() {
        var shell = shell();
        var installed = new AtomicReference<BlockEntity>();
        var level = world(false, installed);
        var manager = WorldObjectManagerApi.getManager(level);
        shell.setLevel(level);
        manager.tickAll();
        assertEquals(0, shell.renderState().energy());
        installed.set(shell);
        manager.tickAll();
        assertEquals(1, shell.renderState().energy());
        verify(level).blockEntityChanged(POS);
        when(level.shouldTickBlocksAt(POS)).thenReturn(false);
        manager.tickAll();
        assertEquals(1, shell.renderState().energy());
        when(level.shouldTickBlocksAt(POS)).thenReturn(true);
        installed.set(null);
        manager.tickAll();
        assertEquals(1, shell.renderState().energy());
        installed.set(shell);
        manager.tickAll();
        assertEquals(2, shell.renderState().energy());
        shell.setRemoved();
        assertEquals(0, manager.size());
    }

    /**
     * The common unload entry point captures data and permits the same shell to be installed again.
     */
    @Test
    void chunkRelease_reinstall_preservesUuidStateAndRejectsOldWork() {
        var shell = shell();
        var installed = new AtomicReference<BlockEntity>(shell);
        var level = world(false, installed);
        var manager = WorldObjectManagerApi.getManager(level);
        shell.setLevel(level);
        manager.tickAll();
        var old = shell.ecsObject().orElseThrow();
        old.defer(() -> fail("Work from an unloaded chunk must not survive"));
        shell.releaseEcsObject();
        assertTrue(old.isDetached());
        assertEquals(0, manager.size());
        shell.clearRemoved();
        assertEquals(old.id(), shell.ecsObject().orElseThrow().id());
        assertEquals(1, shell.renderState().energy());
        manager.tickAll();
        assertEquals(2, shell.renderState().energy());
        shell.setRemoved();
    }

    /**
     * The direct transport updates the client render projection without enabling client simulation.
     */
    @Test
    void clientSnapshot_serverFrame_updatesProjectionWithoutClientTicks() {
        var server = shell();
        var serverLevel = world(false, new AtomicReference<>(server));
        server.setLevel(serverLevel);
        var serverManager = WorldObjectManagerApi.getManager(serverLevel);
        for (int i = 0; i < 20; i++) serverManager.tickAll();
        verify(serverLevel)
                .sendBlockUpdated(POS, server.getBlockState(), server.getBlockState(), 2);
        var client = shell();
        var clientLevel = world(true, new AtomicReference<>(client));
        client.setLevel(clientLevel);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            server.writeClientSnapshot(buffer);
            client.readClientSnapshot(buffer);
            assertEquals(20, client.renderState().energy());
            WorldObjectManagerApi.getManager(clientLevel).tickAll();
            assertEquals(20, client.renderState().energy());
            assertEquals(
                    server.ecsObject().orElseThrow().id(), client.ecsObject().orElseThrow().id());
        } finally {
            buffer.release();
        }
        client.setRemoved();
        server.setRemoved();
    }

    /**
     * Vanilla save hooks produce a real chunk payload, including ValueOutput on modern versions.
     *
     * @throws ReflectiveOperationException if a Minecraft save hook changes
     */
    @Test
    void diskSave_vanillaHook_containsOnlyChunkOwnedArchive() throws ReflectiveOperationException {
        var shell = shell();
        var level = world(false, new AtomicReference<>(shell));
        shell.setLevel(level);
        var manager = WorldObjectManagerApi.getManager(level);
        manager.tickAll();
        var save =
                Arrays.stream(BlockEntity.class.getMethods())
                        .filter(
                                m ->
                                        m.getName().equals("saveWithoutMetadata")
                                                && m.getReturnType() == CompoundTag.class)
                        .findFirst()
                        .orElseThrow();
        Object[] args = new Object[save.getParameterCount()];
        if (args.length != 0) args[0] = lookup(save.getParameterTypes()[0]);
        var tag = (CompoundTag) save.invoke(shell, args);
        assertInstanceOf(CompoundTag.class, tag.get(EcsBlockEntity.ECS_DATA_KEY));
        assertEquals(0, ((net.minecraft.nbt.ListTag) manager.writeNbt().get("objects")).size());
        var id = shell.ecsObject().orElseThrow().id();
        shell.setRemoved();
        var restored = shell();
        load(restored, tag);
        when(level.getBlockEntity(POS)).thenReturn(restored);
        restored.setLevel(level);
        assertEquals(id, restored.ecsObject().orElseThrow().id());
        assertEquals(1, restored.renderState().energy());
        restored.setRemoved();
    }

    /**
     * Initial chunk network data may arrive before vanilla assigns a client world.
     *
     * @throws ReflectiveOperationException if Minecraft load/update hooks change
     */
    @Test
    void vanillaUpdateTag_beforeWorldAssignment_installsPendingClientSnapshot()
            throws ReflectiveOperationException {
        var server = shell();
        var serverLevel = world(false, new AtomicReference<>(server));
        server.setLevel(serverLevel);
        WorldObjectManagerApi.getManager(serverLevel).tickAll();
        var update =
                Arrays.stream(EcsBlockEntity.class.getMethods())
                        .filter(m -> m.getName().equals("getUpdateTag"))
                        .findFirst()
                        .orElseThrow();
        Object[] args = new Object[update.getParameterCount()];
        if (args.length != 0) args[0] = lookup(update.getParameterTypes()[0]);
        var tag = (CompoundTag) update.invoke(server, args);
        var client = shell();
        load(client, tag);
        assertTrue(client.ecsObject().isEmpty());
        var clientLevel = world(true, new AtomicReference<>(client));
        client.setLevel(clientLevel);
        assertEquals(1, client.renderState().energy());
        assertEquals(server.ecsObject().orElseThrow().id(), client.ecsObject().orElseThrow().id());
        WorldObjectManagerApi.getManager(clientLevel).tickAll();
        assertEquals(1, client.renderState().energy());
        assertNotNull(server.getUpdatePacket());
        client.setRemoved();
        server.setRemoved();
    }

    /**
     * Calls the version's real public vanilla load entry point and therefore the shell's selected
     * override.
     *
     * @param shell fresh shell without a world
     * @param tag disk data or vanilla update tag
     * @throws ReflectiveOperationException if Minecraft serialization signatures change
     */
    private static void load(EcsBlockEntity shell, CompoundTag tag)
            throws ReflectiveOperationException {
        var entry =
                Arrays.stream(BlockEntity.class.getMethods())
                        .filter(m -> m.getName().equals("loadWithComponents"))
                        .findFirst()
                        .orElse(null);
        if (entry == null) {
            BlockEntity.class.getMethod("load", CompoundTag.class).invoke(shell, tag);
            return;
        }
        if (entry.getParameterTypes()[0] == CompoundTag.class) {
            entry.invoke(shell, tag, lookup(entry.getParameterTypes()[1]));
            return;
        }
        var tagInput = Class.forName("net.minecraft.world.level.storage.TagValueInput");
        var create =
                Arrays.stream(tagInput.getMethods())
                        .filter(
                                m ->
                                        m.getName().equals("create")
                                                && m.getParameterCount() == 3
                                                && m.getParameterTypes()[2] == CompoundTag.class)
                        .findFirst()
                        .orElseThrow();
        var types = create.getParameterTypes();
        Object input = create.invoke(null, mock(types[0]), lookup(types[1]), tag);
        entry.invoke(shell, input);
    }

    /**
     * Supplies a real registry lookup for version-specific serialization entry points.
     *
     * @param provider version-specific HolderLookup.Provider class
     * @return vanilla registry provider
     * @throws ReflectiveOperationException if the provider factory changes
     */
    private static Object lookup(Class<?> provider) throws ReflectiveOperationException {
        var factory = provider.getMethod("create", java.util.stream.Stream.class);
        return factory.invoke(null, java.util.stream.Stream.empty());
    }

    /**
     * @return unbound shell using a registered vanilla type that accepts the furnace state
     */
    private static GeneratorBlockEntity shell() {
        return new GeneratorBlockEntity(
                BlockEntityFixtures.furnaceType(), POS, Blocks.FURNACE.defaultBlockState());
    }

    /**
     * Mocks only the world boundary; all ECS and BlockEntity code runs normally.
     *
     * @param client whether the controlled world is client-side
     * @param installed exact shell currently visible in the chunk
     * @return registered test world
     */
    private Level world(boolean client, AtomicReference<BlockEntity> installed) {
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(client);
        when(level.hasChunkAt(POS)).thenReturn(true);
        when(level.shouldTickBlocksAt(POS)).thenReturn(true);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        when(level.getBlockState(POS)).thenReturn(Blocks.FURNACE.defaultBlockState());
        when(level.getBlockEntity(POS)).thenAnswer(call -> installed.get());
        WorldObjectManagerApi.registerWorld(level);
        worlds.add(level);
        return level;
    }
}
