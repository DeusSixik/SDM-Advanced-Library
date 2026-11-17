package dev.sixik.sdmal.consumer.ecs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sixik.sdmal.api.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.SnapshotTickableComponent;
import dev.sixik.sdmal.api.ecs.WorldContext;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializers;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializers;

import io.netty.buffer.Unpooled;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

/**
 * Uses only public ECS imports to integrate custom adapters, automatic ticking and component
 * snapshots.
 */
class EcsPublicApiTest {
    /**
     * A consumer's custom value remains supported by both serialization registries after the
     * package split.
     */
    @Test
    void customValue_ticksAndRoundTripsThroughNbtAndDirectBuffer() {
        EcsSerializer<String> strings = EcsSerializers.serializer(String.class);
        EcsSerializers.register(
                Value.class,
                new EcsSerializer<>() {
                    /**
                     * @param value immutable value
                     * @return encoded text
                     */
                    @Override
                    public Tag write(Value value) {
                        return strings.write(value.text());
                    }

                    /**
                     * @param tag owned text tag
                     * @return reconstructed immutable value
                     */
                    @Override
                    public Value read(Tag tag) {
                        return new Value(strings.read(tag));
                    }
                });
        EcsNetworkSerializers.register(
                Value.class,
                new EcsNetworkSerializer<>() {
                    /**
                     * @param buffer destination
                     * @param value immutable value
                     */
                    @Override
                    public void write(FriendlyByteBuf buffer, Value value) {
                        buffer.writeUtf(value.text());
                    }

                    /**
                     * @param buffer source
                     * @return reconstructed immutable value
                     */
                    @Override
                    public Value read(FriendlyByteBuf buffer) {
                        return new Value(buffer.readUtf());
                    }

                    /**
                     * @return stable consumer-owned binary format
                     */
                    @Override
                    public String schemaName() {
                        return "consumer_test:value/v1";
                    }
                });

        try (var manager = new WorldObjectManager()) {
            Component source = manager.create().attach(new Component());
            manager.tickAll();
            assertEquals(new Value("initial!"), source.value);
            CompoundTag saved = new CompoundTag();
            source.writeNbt(saved);
            Component copy = new Component();
            copy.readNbt(saved);
            assertEquals(source.value, copy.value);

            copy.value = new Value("reset");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                source.writeNetwork(buffer);
                copy.readNetwork(buffer);
                assertEquals(source.value, copy.value);
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    /**
     * Consumer-owned value requiring explicit adapters rather than an annotated structural schema.
     *
     * @param text arbitrary immutable test text
     */
    private record Value(String text) {}

    /**
     * Ordinary consumer component; no scheduler registration or internal schema access is needed.
     */
    private static final class Component extends AnnotatedWorldObjectComponent
            implements SnapshotTickableComponent<Value, Value> {
        /**
         * Owner-thread state shared by the persisted and network snapshots.
         */
        @EcsPersist("value")
        @EcsSync("value")
        private Value value = new Value("initial");

        /**
         * @param context owner context
         * @return immutable input value
         */
        @Override
        public Value capture(WorldContext context) {
            return value;
        }

        /**
         * @param snapshot immutable input
         * @param context deferred commands
         * @return next value
         */
        @Override
        public Value compute(Value snapshot, WorldContext context) {
            return new Value(snapshot.text() + "!");
        }

        /**
         * @param result completed value
         * @param context owner context
         */
        @Override
        public void apply(Value result, WorldContext context) {
            value = result;
        }
    }
}
