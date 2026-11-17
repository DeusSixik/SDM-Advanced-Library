package dev.sixik.sdmal.example.ecs;

import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the copyable example's complete persistence/network lifecycle against real Minecraft classes. */
class EcsExampleTest {
    /** Initializes vanilla dependencies without opening a Minecraft world. */
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    /** Removes only the example type explicitly registered by each case. */
    @AfterEach void cleanup() { WorldObjectComponentRegistry.instance().unregister("sdmal_example:generator"); }

    /** Service discovery is repeatable and runs before durable objects are created. */
    @Test void example_serviceDiscovery_registersBeforeCreatingObject() {
        try (var manager = new WorldObjectManager()) {
            assertThrows(IllegalStateException.class, () -> EcsExample.createGenerator(manager));
            assertEquals(0, manager.size());
            WorldObjectComponentRegistry.instance().registerServices();
            WorldObjectComponentRegistry.instance().registerServices();
            assertEquals(1, EcsExample.createGenerator(manager).size());
        }
    }

    /** The archive restores the UUID, record, owner and scheduling while unsaved fields keep constructor defaults. */
    @Test void example_worldArchive_restoresOnlyAnnotatedState() {
        WorldObjectComponentRegistry.instance().registerServices();
        UUID owner = UUID.randomUUID();
        try (var source = new WorldObjectManager(); var restored = new WorldObjectManager()) {
            var object = EcsExample.createGenerator(source);
            var generator = object.getSingle(EcsExample.generatorType()).orElseThrow();
            generator.setOwnerId(owner);
            source.tickAll();
            generator.setRunning(false);
            assertEquals(new GeneratorComponent.Battery(1, 100), generator.battery());

            assertTrue(restored.readNbt(source.writeNbt()).isEmpty());
            var restoredObject = restored.get(object.id()).orElseThrow();
            var copy = restoredObject.getSingle(EcsExample.generatorType()).orElseThrow();
            assertEquals(generator.battery(), copy.battery());
            assertEquals(owner, copy.ownerId());
            assertTrue(copy.isRunning());
            assertEquals(0, copy.localTicks());
            assertEquals(20, restoredObject.tickInterval());
        }
    }

    /** Independent snapshot bytes can be dispatched later and change only the mirror's network-selected fields. */
    @Test void example_binarySnapshot_updatesSleepingMirrorWithoutSendingServerOnlyState() {
        WorldObjectComponentRegistry.instance().registerServices();
        try (var server = new WorldObjectManager(); var client = new WorldObjectManager()) {
            var object = EcsExample.createGenerator(server);
            var source = object.getSingle(EcsExample.generatorType()).orElseThrow();
            source.setOwnerId(UUID.randomUUID());
            server.tickAll();
            source.setRunning(false);

            byte[] snapshot;
            FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
            try {
                source.writeNetwork(output);
                snapshot = new byte[output.readableBytes()];
                output.readBytes(snapshot);
            } finally { output.release(); }

            var mirrorObject = new dev.sixik.sdmal.api.ecs.WorldObject(object.id());
            mirrorObject.setPersistent(false);
            mirrorObject.sleep();
            client.add(mirrorObject);
            var mirror = mirrorObject.attach(new GeneratorComponent(), EcsExample.generatorType());
            UUID localOwner = UUID.randomUUID();
            mirror.setOwnerId(localOwner);
            FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(snapshot));
            try { mirror.readNetwork(input); assertEquals(0, input.readableBytes()); }
            finally { input.release(); }

            client.tickAll();
            assertEquals(source.battery(), mirror.battery());
            assertFalse(mirror.isRunning());
            assertEquals(localOwner, mirror.ownerId());
            assertEquals(0, mirror.localTicks());
        }
    }

    /** The example's interval, running switch and record validation remain consistent during ordinary ticks. */
    @Test void example_ticksEveryTwentyStepsAndStops_atCapacityOrWhenDisabled() {
        WorldObjectComponentRegistry.instance().registerServices();
        try (var manager = new WorldObjectManager()) {
            var generator = EcsExample.createGenerator(manager).getSingle(EcsExample.generatorType()).orElseThrow();
            for (int index = 0; index < 2001; index++) manager.tickAll();
            assertEquals(new GeneratorComponent.Battery(100, 100), generator.battery());
            assertEquals(101, generator.localTicks());
            generator.setRunning(false);
            for (int index = 0; index < 20; index++) manager.tickAll();
            assertEquals(102, generator.localTicks());
            assertEquals(100, generator.battery().stored());
            assertThrows(IllegalArgumentException.class, () -> new GeneratorComponent.Battery(101, 100));
            assertThrows(IllegalArgumentException.class, () -> new GeneratorComponent.Battery(-1, 100));
        }
    }
}
