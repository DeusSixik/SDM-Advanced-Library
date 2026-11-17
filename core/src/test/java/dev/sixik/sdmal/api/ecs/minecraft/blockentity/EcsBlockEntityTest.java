package dev.sixik.sdmal.api.ecs.minecraft.blockentity;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import dev.sixik.sdmal.example.ecs.blockentity.BlockGeneratorComponent;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlock;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Exercises standalone Minecraft shells, service discovery and immutable render publication.
 */
class EcsBlockEntityTest {
    /**
     * Initializes vanilla types used by constructor and rendering tests.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldObjectComponentRegistry.instance().registerServices();
    }

    /**
     * Constructors never call subclass hooks or allocate an ECS object before world installation.
     */
    @Test
    void shell_withoutWorld_staysInactiveAndRemovalIsIdempotent() {
        var shell =
                new GeneratorBlockEntity(
                        BlockEntityFixtures.furnaceType(),
                        new BlockPos(2, 7, -4),
                        Blocks.FURNACE.defaultBlockState());
        assertTrue(shell.ecsObject().isEmpty());
        assertTrue(shell.ecsRestoreFailure().isEmpty());
        assertNull(shell.renderState());
        shell.setRemoved();
        shell.releaseEcsObject();
        shell.clearRemoved();
        assertTrue(shell.ecsObject().isEmpty());
    }

    /**
     * Both built-in and example components are found by the real annotation-processor service
     * descriptor.
     */
    @Test
    void services_blockEntityTypes_areAutomaticallyRegistered() {
        var registry = WorldObjectComponentRegistry.instance();
        assertEquals("sdmal:block_entity", registry.require(BlockEntityComponent.class).id());
        assertEquals(
                "sdmal_example:block_generator",
                registry.require(BlockGeneratorComponent.class).id());
    }

    /**
     * Position derives from the shell; an uninstalled host cannot simulate or touch a world.
     */
    @Test
    void position_uninstalledHost_isImmutableAndInactive() {
        var pos = new BlockPos.MutableBlockPos(8, 16, 32);
        var shell =
                new GeneratorBlockEntity(
                        BlockEntityFixtures.furnaceType(), pos, Blocks.FURNACE.defaultBlockState());
        var binding = new BlockEntityComponent();
        binding.bind(shell);
        pos.set(0, 0, 0);
        assertEquals(new BlockPos(8, 16, 32), binding.position());
        assertFalse(binding.getAsBoolean());
        assertDoesNotThrow(binding::setChanged);
        assertDoesNotThrow(binding::requestClientUpdate);
    }

    /**
     * Renderers can read a published immutable value from another thread; publication belongs to
     * the world thread.
     *
     * @throws Exception if a worker does not finish
     */
    @Test
    void renderState_publication_enforcesOwnerAndAllowsRendererRead() throws Exception {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            var render = object.attach(new RenderProbe());
            render.publish("ready");
            assertEquals(
                    "ready", CompletableFuture.supplyAsync(render::state).get(5, TimeUnit.SECONDS));
            CompletableFuture.runAsync(
                            () ->
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> render.publish("wrong")))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("ready", render.state());
        }
    }

    /**
     * Runtime descriptors never persist a Minecraft world, host reference or derived coordinate.
     */
    @Test
    void position_componentSerialization_containsNoRuntimeState() {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            object.attach(
                    new BlockEntityComponent(),
                    WorldObjectComponentRegistry.instance().require(BlockEntityComponent.class));
            var archive = manager.writeNbt();
            var record =
                    (net.minecraft.nbt.CompoundTag)
                            ((net.minecraft.nbt.ListTag) archive.get("objects")).get(0);
            var component =
                    (net.minecraft.nbt.CompoundTag)
                            ((net.minecraft.nbt.ListTag) record.get("components")).get(0);
            assertTrue(((net.minecraft.nbt.CompoundTag) component.get("data")).isEmpty());
        }
    }

    /**
     * Example blocks inherit a null vanilla ticker and construct only a thin ECS shell.
     */
    @Test
    void exampleBlock_factory_inheritsNullVanillaTicker() throws NoSuchMethodException {
        assertTrue(EntityBlock.class.isAssignableFrom(GeneratorBlock.class));
        var ticker =
                GeneratorBlock.class.getMethod(
                        "getTicker",
                        net.minecraft.world.level.Level.class,
                        net.minecraft.world.level.block.state.BlockState.class,
                        BlockEntityType.class);
        assertEquals(EntityBlock.class, ticker.getDeclaringClass());
    }

    /**
     * Exposes protected publication without adding mutable renderer state.
     */
    private static final class RenderProbe extends RenderStateComponent<String> {
        /**
         * @param state immutable renderer string
         */
        void publish(String state) {
            publishState(state);
        }
    }
}
