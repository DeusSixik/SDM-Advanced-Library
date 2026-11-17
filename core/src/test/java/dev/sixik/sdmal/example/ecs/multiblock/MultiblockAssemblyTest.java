package dev.sixik.sdmal.example.ecs.multiblock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.minecraft.WorldObjectManagerApi;
import dev.sixik.sdmal.example.item.ExampleItem;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies assembly through the item interaction used by vanilla's sneaking click path.
 */
class MultiblockAssemblyTest {
    /**
     * Fixture controller coordinate, distinct from every required part.
     */
    private static final BlockPos ORIGIN = new BlockPos(8, 64, 8);

    /**
     * Mutable fixture block states, independent of Mockito's reused position arguments.
     */
    private final Map<Long, BlockState> blocks = new HashMap<>();

    /**
     * Independent coordinates actually written by assembly.
     */
    private final List<BlockPos> writes = new ArrayList<>();

    /**
     * Registered fixture world released after each case; null before setup.
     */
    private Level level;

    /**
     * Actual controller shell, with real ECS composition after setup.
     */
    private MultiblockControllerBlockEntity controller;

    /**
     * Controlled interacting player with build permission by default.
     */
    private Player player;

    /**
     * Initializes vanilla block types and library component services.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldObjectComponentRegistry.instance().registerServices();
    }

    /**
     * Releases actual ECS lifecycle state even after assertion failure.
     */
    @AfterEach
    void cleanup() {
        if (level != null) WorldObjectManagerApi.unregisterWorld(level);
    }

    /**
     * A sneaking item click fills all nine parts and production begins through the normal next ECS
     * tick.
     */
    @Test
    void useOn_sneakingController_buildsAndStartsNormalProduction() {
        setupWorld();
        var id = controller.ecsObject().orElseThrow().id();
        assertSame(InteractionResult.SUCCESS, item().useOn(context()));
        assertEquals(9, writes.size());
        assertEquals(
                MultiblockPattern.COMPLETE_MASK,
                MultiblockPattern.matches(level, ORIGIN, new BlockPos.MutableBlockPos()));
        assertFalse(writes.contains(ORIGIN));
        assertEquals(id, controller.ecsObject().orElseThrow().id());
        assertEquals(0, controller.renderState().energy());
        WorldObjectManagerApi.getManager(level).tickAll();
        assertEquals(10, controller.renderState().energy());
        assertEquals(MultiblockStatus.FORMED, controller.renderState().status());
        var message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendSystemMessage(message.capture());
        var content =
                assertInstanceOf(TranslatableContents.class, message.getValue().getContents());
        assertEquals("message.sdmal.ecs_multiblock.assembly.built", content.getKey());
        verify(player, never()).getInventory();
    }

    /**
     * Existing correct blocks and energy remain intact, and a repeated click performs no writes.
     */
    @Test
    void assemble_partialAndRepeated_preservesExistingPartsEnergyAndIdentity() {
        setupWorld();
        blocks.put(part(0).asLong(), Blocks.IRON_BLOCK.defaultBlockState());
        assertEquals(MultiblockAssembler.Result.BUILT, assemble());
        assertEquals(8, writes.size());
        assertFalse(writes.contains(part(0)));
        WorldObjectManagerApi.getManager(level).tickAll();
        var object = controller.ecsObject().orElseThrow();
        writes.clear();
        assertEquals(MultiblockAssembler.Result.ALREADY_COMPLETE, assemble());
        assertTrue(writes.isEmpty());
        assertSame(object, controller.ecsObject().orElseThrow());
        assertEquals(10, controller.renderState().energy());
    }

    /**
     * A late obstacle is found in preflight before earlier empty positions can be changed.
     */
    @Test
    void assemble_occupiedCore_rejectsBeforeAnyPlacement() {
        setupWorld();
        blocks.put(part(8).asLong(), Blocks.CHEST.defaultBlockState());
        assertEquals(MultiblockAssembler.Result.BLOCKED, assemble());
        assertTrue(writes.isEmpty());
        assertTrue(blocks.get(part(8).asLong()).is(Blocks.CHEST));
    }

    /**
     * Absent required chunks are never loaded or read for placement.
     */
    @Test
    void assemble_unloadedPart_doesNotReadStatesOrPlaceBlocks() {
        setupWorld();
        when(level.hasChunkAt(any(BlockPos.class)))
                .thenAnswer(call -> !part(8).equals(call.getArgument(0)));
        assertEquals(MultiblockAssembler.Result.UNLOADED, assemble());
        assertTrue(writes.isEmpty());
        verify(level, never()).getBlockState(any(BlockPos.class));
    }

    /**
     * Structure bounds are checked before all placement.
     */
    @Test
    void assemble_outsideBuildHeight_doesNotPlaceBlocks() {
        setupWorld();
        when(level.isOutsideBuildHeight(any(BlockPos.class)))
                .thenAnswer(call -> ((BlockPos) call.getArgument(0)).getY() > ORIGIN.getY());
        assertEquals(MultiblockAssembler.Result.OUT_OF_BOUNDS, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * World-border violations cannot be filled by the demonstration shortcut.
     */
    @Test
    void assemble_outsideBorder_doesNotPlaceBlocks() {
        setupWorld();
        level.getWorldBorder().setCenter(ORIGIN.getX(), ORIGIN.getZ());
        level.getWorldBorder().setSize(1);
        assertEquals(MultiblockAssembler.Result.OUT_OF_BOUNDS, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * Read-only game modes cannot build even when an item use reaches the server.
     */
    @Test
    void assemble_noBuildPermission_doesNotPlaceBlocks() {
        setupWorld();
        when(player.mayBuild()).thenReturn(false);
        assertEquals(MultiblockAssembler.Result.DENIED, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * The shortcut never permits spectator world mutation.
     */
    @Test
    void assemble_spectator_doesNotPlaceBlocks() {
        setupWorld();
        when(player.isSpectator()).thenReturn(true);
        assertEquals(MultiblockAssembler.Result.DENIED, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * Every required position participates in the world permission preflight.
     */
    @Test
    void assemble_protectedPart_doesNotPartiallyBuild() {
        setupWorld();
        when(level.mayInteract(eq(player), any(BlockPos.class)))
                .thenAnswer(call -> !part(8).equals(call.getArgument(1)));
        assertEquals(MultiblockAssembler.Result.DENIED, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * Another shell at the same coordinate invalidates a stale assembly request.
     */
    @Test
    void assemble_replacedController_doesNotPlaceBlocks() {
        setupWorld();
        when(level.getBlockEntity(ORIGIN)).thenReturn(null);
        assertEquals(MultiblockAssembler.Result.INTERRUPTED, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * A declined world placement reports failure instead of claiming a completed structure.
     */
    @Test
    void assemble_setBlockRejected_reportsInterruption() {
        setupWorld();
        doReturn(false).when(level).setBlock(any(BlockPos.class), any(BlockState.class), eq(3));
        assertEquals(MultiblockAssembler.Result.INTERRUPTED, assemble());
        assertTrue(writes.isEmpty());
    }

    /**
     * Neighbor callback side effects are rechecked before the next write, preserving new obstacles.
     */
    @Test
    void assemble_callbackPlacesObstacle_stopsWithoutReplacingIt() {
        setupWorld();
        doAnswer(
                        call -> {
                            BlockPos position = ((BlockPos) call.getArgument(0)).immutable();
                            writes.add(position);
                            blocks.put(position.asLong(), call.getArgument(1));
                            blocks.put(part(1).asLong(), Blocks.CHEST.defaultBlockState());
                            return true;
                        })
                .when(level)
                .setBlock(any(BlockPos.class), any(BlockState.class), eq(3));
        assertEquals(MultiblockAssembler.Result.INTERRUPTED, assemble());
        assertEquals(List.of(part(0)), writes);
        assertTrue(blocks.get(part(1).asLong()).is(Blocks.CHEST));
    }

    /**
     * Normal item use passes through so the ordinary block debug interaction remains available.
     */
    @Test
    void useOn_notSneaking_doesNotAssemble() {
        setupWorld();
        when(player.isShiftKeyDown()).thenReturn(false);
        assertSame(InteractionResult.PASS, item().useOn(context()));
        assertTrue(writes.isEmpty());
        verify(player, never()).sendSystemMessage(any(Component.class));
    }

    /**
     * A sneaking click on unrelated blocks is left to normal item handling.
     */
    @Test
    void useOn_otherBlock_doesNotAssemble() {
        setupWorld();
        when(level.getBlockEntity(ORIGIN)).thenReturn(null);
        assertSame(InteractionResult.PASS, item().useOn(context()));
        assertTrue(writes.isEmpty());
    }

    /**
     * Automated contexts without a player cannot trigger the demo shortcut.
     */
    @Test
    void useOn_noPlayer_passesThrough() {
        var context = mock(UseOnContext.class);
        when(context.getItemInHand()).thenReturn(mock(ItemStack.class));
        assertSame(InteractionResult.PASS, item().useOn(context));
        verify(context, never()).getLevel();
    }

    /**
     * Client prediction consumes the gesture without local construction or duplicate feedback.
     */
    @Test
    void useOn_clientPrediction_doesNotMutateOrSendMessages() {
        setupWorld();
        when(level.isClientSide()).thenReturn(true);
        assertSame(InteractionResult.SUCCESS, item().useOn(context()));
        assertTrue(writes.isEmpty());
        verify(player, never()).sendSystemMessage(any(Component.class));
    }

    /**
     * @return outcome of the real server builder for this fixture
     */
    private MultiblockAssembler.Result assemble() {
        return MultiblockAssembler.assemble(level, controller, player);
    }

    /**
     * @return real item behavior without constructing a new item in Minecraft's frozen registries
     */
    private static ExampleItem item() {
        return mock(ExampleItem.class, CALLS_REAL_METHODS);
    }

    /**
     * @return controlled use context for the actual player's clicked controller
     */
    private UseOnContext context() {
        var context = mock(UseOnContext.class);
        when(context.getPlayer()).thenReturn(player);
        when(context.getLevel()).thenReturn(level);
        when(context.getClickedPos()).thenReturn(ORIGIN);
        when(context.getItemInHand()).thenReturn(mock(ItemStack.class));
        return context;
    }

    /**
     * Resolves a required-part position independently of the reusable placement cursor.
     *
     * @param index required-part ordinal
     * @return immutable world coordinate
     */
    private static BlockPos part(int index) {
        var pos = new BlockPos.MutableBlockPos();
        MultiblockPattern.position(ORIGIN, index, pos);
        return pos.immutable();
    }

    /**
     * Initializes actual shell/ECS lifecycle against a mutable controlled Level and permitted
     * player.
     */
    private void setupWorld() {
        level = mock(Level.class);
        player = mock(Player.class);
        when(player.isShiftKeyDown()).thenReturn(true);
        when(player.mayBuild()).thenReturn(true);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        when(level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        when(level.shouldTickBlocksAt(ORIGIN)).thenReturn(true);
        when(level.mayInteract(eq(player), any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class)))
                .thenAnswer(
                        call -> {
                            BlockPos position = call.getArgument(0);
                            if (position.equals(ORIGIN)) return Blocks.FURNACE.defaultBlockState();
                            return blocks.getOrDefault(
                                    position.asLong(), Blocks.AIR.defaultBlockState());
                        });
        when(level.setBlock(any(BlockPos.class), any(BlockState.class), eq(3)))
                .thenAnswer(
                        call -> {
                            BlockPos position = ((BlockPos) call.getArgument(0)).immutable();
                            writes.add(position);
                            blocks.put(position.asLong(), call.getArgument(1));
                            return true;
                        });
        try {
            Class<?> holder;
            try {
                holder = Class.forName("net.minecraft.world.level.block.entity.BlockEntityTypes");
            } catch (ClassNotFoundException legacy) {
                holder = BlockEntityType.class;
            }
            controller =
                    new MultiblockControllerBlockEntity(
                            (BlockEntityType<?>) holder.getField("FURNACE").get(null),
                            ORIGIN,
                            Blocks.FURNACE.defaultBlockState());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        when(level.getBlockEntity(ORIGIN)).thenReturn(controller);
        WorldObjectManagerApi.registerWorld(level);
        controller.setLevel(level);
    }
}
