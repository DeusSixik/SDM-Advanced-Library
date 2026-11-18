package dev.sixik.sdmal.example.ecs.multiblock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.minecraft.LevelEcsManagers;
import dev.sixik.sdmal.example.ecs.blockentity.EcsExampleBlock;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises real controller/component scheduling against a mutable, controlled Level boundary.
 */
class MultiblockExampleTest {
    /**
     * Controller coordinate at a chunk edge so the example spans four chunks.
     */
    private static final BlockPos ORIGIN = new BlockPos(15, 64, 15);

    /**
     * Registered fixture worlds released after each test.
     */
    private final List<Level> worlds = new ArrayList<>();

    /**
     * Independent fixture block states indexed by packed immutable coordinates.
     */
    private final Map<Long, BlockState> blocks = new HashMap<>();

    /**
     * Positions made unavailable without changing the underlying block state.
     */
    private final Set<Long> unloaded = new HashSet<>();

    /**
     * Number of required-part reads, excluding vanilla dirty-mark reads at the controller.
     */
    private final AtomicInteger partReads = new AtomicInteger();

    /**
     * Initializes real Minecraft registries and annotation-driven component services.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ComponentRegistry.instance().registerServices();
    }

    /**
     * Releases all fixture managers even when a test fails.
     */
    @AfterEach
    void cleanup() {
        worlds.forEach(LevelEcsManagers::unregisterWorld);
    }

    /**
     * The nine required offsets are distinct, omit the controller and handle negative world
     * coordinates.
     */
    @Test
    void pattern_offsets_coversEightSupportsAndOneCore() {
        var origin = new BlockPos(-16, -4, -16);
        var cursor = new BlockPos.MutableBlockPos();
        Set<BlockPos> positions = new HashSet<>();
        for (int i = 0; i < MultiblockPattern.PARTS; i++) {
            MultiblockPattern.position(origin, i, cursor);
            assertNotEquals(origin, cursor);
            assertTrue(positions.add(cursor.immutable()));
            assertEquals(i == 8 ? -3 : -4, cursor.getY());
        }
        assertEquals(9, positions.size());
        assertTrue(positions.contains(new BlockPos(-16, -3, -16)));
    }

    /**
     * Every required part has a unique diagnostic bit; a missing core cannot be replaced with iron.
     */
    @Test
    void pattern_missingEachPart_clearsOnlyItsMaskBit() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        var cursor = new BlockPos.MutableBlockPos();
        assertEquals(
                MultiblockPattern.COMPLETE_MASK, MultiblockPattern.matches(level, ORIGIN, cursor));
        for (int i = 0; i < MultiblockPattern.PARTS; i++) {
            long key = part(i).asLong();
            var expected =
                    blocks.put(
                            key,
                            i == 8
                                    ? Blocks.IRON_BLOCK.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState());
            assertEquals(
                    MultiblockPattern.COMPLETE_MASK & ~(1 << i),
                    MultiblockPattern.matches(level, ORIGIN, cursor));
            blocks.put(key, expected);
        }
    }

    /**
     * Missing structures never produce, assembled structures produce ten units and repairs resume
     * immediately.
     */
    @Test
    void worldTicks_buildBreakRepair_controlsProduction() {
        var shell = shell();
        var level = world(shell, false);
        shell.setLevel(level);
        var manager = LevelEcsManagers.getManager(level);
        manager.tickAll();
        assertEquals(MultiblockStatus.INCOMPLETE, shell.renderState().status());
        assertEquals(0, shell.renderState().energy());
        fillStructure();
        manager.tickAll();
        assertEquals(MultiblockStatus.FORMED, shell.renderState().status());
        assertEquals(10, shell.renderState().energy());
        assertEquals(9, shell.renderState().matchedParts());
        assertEquals(-1, shell.renderState().missingPart());
        blocks.remove(part(3).asLong());
        manager.tickAll();
        assertEquals(MultiblockStatus.INCOMPLETE, shell.renderState().status());
        assertEquals(10, shell.renderState().energy());
        assertEquals(8, shell.renderState().matchedParts());
        assertEquals(3, shell.renderState().missingPart());
        fillStructure();
        manager.tickAll();
        assertEquals(20, shell.renderState().energy());
        shell.setRemoved();
        assertEquals(0, manager.size());
    }

    /**
     * An absent required chunk prevents every part read and cannot generate energy from cached
     * formation.
     */
    @Test
    void worldTicks_unloadedPart_preflightsBeforeReadingAndStopsProduction() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        shell.setLevel(level);
        var manager = LevelEcsManagers.getManager(level);
        manager.tickAll();
        unloaded.add(part(2).asLong());
        partReads.set(0);
        manager.tickAll();
        assertEquals(0, partReads.get());
        assertEquals(MultiblockStatus.UNLOADED, shell.renderState().status());
        assertEquals(10, shell.renderState().energy());
        unloaded.clear();
        manager.tickAll();
        assertEquals(20, shell.renderState().energy());
        shell.setRemoved();
    }

    /**
     * A part destroyed after capture must not allow one stale worker result to produce energy.
     */
    @Test
    void apply_partChangedAfterCompute_revalidatesBeforeCommit() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        shell.setLevel(level);
        var component =
                shell.ecsObject()
                        .orElseThrow()
                        .getSingle(MultiblockGeneratorComponent.class)
                        .orElseThrow();
        var context = LevelEcsManagers.getManager(level).context();
        var result = component.compute(component.capture(context), context);
        assertEquals(10, result.nextEnergy);
        blocks.remove(part(8).asLong());
        component.apply(result, context);
        assertEquals(0, shell.renderState().energy());
        assertEquals(MultiblockStatus.INCOMPLETE, shell.renderState().status());
        assertEquals(8, shell.renderState().missingPart());
        shell.setRemoved();
    }

    /**
     * Chunk disappearance during computation is also checked again before production commit.
     */
    @Test
    void apply_chunkUnloadedAfterCapture_discardsProduction() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        shell.setLevel(level);
        var component =
                shell.ecsObject()
                        .orElseThrow()
                        .getSingle(MultiblockGeneratorComponent.class)
                        .orElseThrow();
        var context = LevelEcsManagers.getManager(level).context();
        var result = component.compute(component.capture(context), context);
        unloaded.add(part(1).asLong());
        partReads.set(0);
        component.apply(result, context);
        assertEquals(0, partReads.get());
        assertEquals(0, shell.renderState().energy());
        assertEquals(MultiblockStatus.UNLOADED, shell.renderState().status());
        shell.setRemoved();
    }

    /**
     * Build-height and world-border failures stop inspection before any block read.
     */
    @Test
    void availability_outsideBounds_doesNotReadOrProduce() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        when(level.isOutsideBuildHeight(any(BlockPos.class)))
                .thenAnswer(call -> ((BlockPos) call.getArgument(0)).getY() > ORIGIN.getY());
        shell.setLevel(level);
        LevelEcsManagers.getManager(level).tickAll();
        assertEquals(0, partReads.get());
        assertEquals(MultiblockStatus.OUT_OF_BOUNDS, shell.renderState().status());
        shell.setRemoved();
    }

    /**
     * Parts outside the world border cannot form even when the controller itself is inside it.
     */
    @Test
    void availability_outsideBorder_doesNotReadOrProduce() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        level.getWorldBorder().setCenter(ORIGIN.getX(), ORIGIN.getZ());
        level.getWorldBorder().setSize(1);
        shell.setLevel(level);
        LevelEcsManagers.getManager(level).tickAll();
        assertEquals(0, partReads.get());
        assertEquals(MultiblockStatus.OUT_OF_BOUNDS, shell.renderState().status());
        shell.setRemoved();
    }

    /**
     * Structure metadata stays transient while energy and UUID survive a real hosted
     * unload/reinstall.
     */
    @Test
    void lifecycle_reload_preservesEnergyAndRechecksFormation() {
        fillStructure();
        var shell = shell();
        var level = world(shell, false);
        shell.setLevel(level);
        var manager = LevelEcsManagers.getManager(level);
        manager.tickAll();
        var old = shell.ecsObject().orElseThrow();
        var component = old.getSingle(MultiblockGeneratorComponent.class).orElseThrow();
        var data = new CompoundTag();
        component.writeNbt(data);
        assertNotNull(data.get("energy"));
        assertNull(data.get("status"));
        assertNull(data.get("matchedMask"));
        old.defer(() -> fail("Old controller work must not survive chunk unload"));
        shell.releaseEcsObject();
        blocks.remove(part(8).asLong());
        shell.clearRemoved();
        assertEquals(old.id(), shell.ecsObject().orElseThrow().id());
        assertEquals(10, shell.renderState().energy());
        assertEquals(MultiblockStatus.CHECKING, shell.renderState().status());
        manager.tickAll();
        assertEquals(MultiblockStatus.INCOMPLETE, shell.renderState().status());
        assertEquals(10, shell.renderState().energy());
        assertTrue(old.isDetached());
        shell.setRemoved();
    }

    /**
     * The full binary snapshot includes energy and transient formation, and the client never
     * simulates production.
     */
    @Test
    void network_clientMirror_receivesStatusWithoutClientProduction() {
        fillStructure();
        var server = shell();
        var serverLevel = world(server, false);
        server.setLevel(serverLevel);
        LevelEcsManagers.getManager(serverLevel).tickAll();
        var client = shell();
        var clientLevel = world(client, true);
        client.setLevel(clientLevel);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            server.writeClientSnapshot(buffer);
            client.readClientSnapshot(buffer);
            assertEquals(server.renderState(), client.renderState());
            LevelEcsManagers.getManager(clientLevel).tickAll();
            assertEquals(10, client.renderState().energy());
            verify(clientLevel, never()).getBlockState(any(BlockPos.class));
        } finally {
            buffer.release();
        }
        server.setRemoved();
        client.setRemoved();
    }

    /**
     * Server clicks display formation plus the first missing block's exact coordinate.
     *
     * @throws ReflectiveOperationException if the version's interaction signature changes
     */
    @Test
    void rightClick_incompleteController_reportsStatusAndMissingCoordinate()
            throws ReflectiveOperationException {
        var shell = shell();
        var level = world(shell, false);
        shell.setLevel(level);
        LevelEcsManagers.getManager(level).tickAll();
        var player = mock(Player.class);
        var block = mock(MultiblockControllerBlock.class, CALLS_REAL_METHODS);
        var method =
                Arrays.stream(EcsExampleBlock.class.getDeclaredMethods())
                        .filter(
                                m ->
                                        m.getName().equals("use")
                                                || m.getName().equals("useWithoutItem"))
                        .findFirst()
                        .orElseThrow();
        method.setAccessible(true);
        if (method.getName().equals("use"))
            method.invoke(
                    block,
                    Blocks.FURNACE.defaultBlockState(),
                    level,
                    ORIGIN,
                    player,
                    InteractionHand.MAIN_HAND,
                    mock(BlockHitResult.class));
        else
            method.invoke(
                    block,
                    Blocks.FURNACE.defaultBlockState(),
                    level,
                    ORIGIN,
                    player,
                    mock(BlockHitResult.class));
        var messages = ArgumentCaptor.forClass(Component.class);
        verify(player, times(2)).sendSystemMessage(messages.capture());
        var status =
                assertInstanceOf(
                        TranslatableContents.class, messages.getAllValues().get(0).getContents());
        assertEquals("message.sdmal.ecs_multiblock.status", status.getKey());
        assertEquals(0, status.getArgs()[1]);
        assertEquals(9, status.getArgs()[4]);
        var missing =
                assertInstanceOf(
                        TranslatableContents.class, messages.getAllValues().get(1).getContents());
        assertEquals("message.sdmal.ecs_multiblock.missing", missing.getKey());
        assertArrayEquals(new Object[] {14, 64, 14}, Arrays.copyOfRange(missing.getArgs(), 1, 4));
        shell.setRemoved();
    }

    /**
     * Reused primitive frames cap correctly without overflow even for invalid saved integer values.
     *
     * @param energy captured stored value
     * @param expected bounded result
     */
    @ParameterizedTest
    @CsvSource({"-1,10", "0,10", "9995,10000", "10000,10000", "2147483647,10000"})
    void compute_energyBounds_capsWithoutOverflow(int energy, int expected) {
        var frame = new MultiblockGeneratorComponent.Frame();
        frame.energy = energy;
        frame.status = MultiblockStatus.FORMED;
        var component = new MultiblockGeneratorComponent();
        assertSame(frame, component.compute(frame, null));
        assertEquals(expected, frame.nextEnergy);
    }

    /**
     * Fills only the required ordinary support/core blocks; no additional BlockEntities are
     * created.
     */
    private void fillStructure() {
        for (int i = 0; i < MultiblockPattern.PARTS; i++) {
            blocks.put(
                    part(i).asLong(),
                    (i == 8 ? Blocks.REDSTONE_BLOCK : Blocks.IRON_BLOCK).defaultBlockState());
        }
    }

    /**
     * Returns an independent required-part coordinate for mutation of the fixture world.
     *
     * @param index required-part ordinal
     * @return immutable coordinate
     */
    private static BlockPos part(int index) {
        var pos = new BlockPos.MutableBlockPos();
        MultiblockPattern.position(ORIGIN, index, pos);
        return pos.immutable();
    }

    /**
     * @return unbound controller shell with a registered vanilla fixture type that accepts the
     *     supplied state
     */
    private static MultiblockControllerBlockEntity shell() {
        try {
            Class<?> holder;
            try {
                holder = Class.forName("net.minecraft.world.level.block.entity.BlockEntityTypes");
            } catch (ClassNotFoundException legacy) {
                holder = BlockEntityType.class;
            }
            return new MultiblockControllerBlockEntity(
                    (BlockEntityType<?>) holder.getField("FURNACE").get(null),
                    ORIGIN,
                    Blocks.FURNACE.defaultBlockState());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * Mocks world access while executing real shell lifecycle, ECS scheduling and snapshot code.
     *
     * @param shell exact installed controller
     * @param client side flag
     * @return registered world fixture
     */
    private Level world(MultiblockControllerBlockEntity shell, boolean client) {
        var level = mock(Level.class);
        when(level.isClientSide()).thenReturn(client);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        when(level.hasChunkAt(any(BlockPos.class)))
                .thenAnswer(call -> !unloaded.contains(((BlockPos) call.getArgument(0)).asLong()));
        when(level.shouldTickBlocksAt(ORIGIN)).thenReturn(true);
        when(level.getBlockEntity(ORIGIN)).thenReturn(shell);
        when(level.getBlockState(any(BlockPos.class)))
                .thenAnswer(
                        call -> {
                            BlockPos position = call.getArgument(0);
                            if (position.equals(ORIGIN)) return Blocks.FURNACE.defaultBlockState();
                            partReads.incrementAndGet();
                            assertFalse(
                                    unloaded.contains(position.asLong()),
                                    "Must never read an unloaded required position");
                            return blocks.getOrDefault(
                                    position.asLong(), Blocks.AIR.defaultBlockState());
                        });
        LevelEcsManagers.registerWorld(level);
        worlds.add(level);
        return level;
    }
}
