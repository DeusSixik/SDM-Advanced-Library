package dev.sixik.sdmal.api.ecs.sync;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

/**
 * Exercises leased primitive staging under malformed packets, codec failures and recursive encodes.
 */
class DeltaBufferTest {
    /**
     * Failed encoding must not acknowledge unsent revisions or retain stale staging references.
     */
    @Test
    void writeFailure_reusingTracker_retriesEveryUnacknowledgedField() {
        var source = new Pair();
        var tracker = new NetworkComponent.DeltaTracker();
        var destination = new Pair();
        var receiving = new NetworkComponent.DeltaTracker();
        FriendlyByteBuf buffer = buffer();
        try {
            source.a.set(10);
            source.b.set(20);
            source.onWrite =
                    () -> {
                        throw new IllegalStateException("codec failed");
                    };
            assertThrows(IllegalStateException.class, () -> source.writeDelta(buffer, tracker));
            assertEquals(-1, tracker.known(0));
            assertEquals(-1, tracker.known(1));
            buffer.clear();
            assertTrue(source.writeDelta(buffer, tracker));
            destination.readDelta(buffer, receiving);
            assertEquals(10, destination.a.get());
            assertEquals(20, destination.b.get());
            buffer.clear();
            assertFalse(source.writeDelta(buffer, tracker));
            assertEquals(0, buffer.writerIndex());
        } finally {
            buffer.release();
        }
    }

    /**
     * Duplicate markers and decoded values from a rejected packet must not poison the next valid
     * packet.
     */
    @Test
    void readFailure_reusingTracker_clearsSeenFieldsAndKeepsAtomicDecode() {
        var destination = new Pair();
        var tracker = new NetworkComponent.DeltaTracker();
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(2);
            buffer.writeVarInt(0);
            buffer.writeVarLong(1);
            buffer.writeVarInt(10);
            buffer.writeVarInt(0);
            buffer.writeVarLong(2);
            buffer.writeVarInt(20);
            assertThrows(SyncException.class, () -> destination.readDelta(buffer, tracker));
            assertEquals(0, destination.a.get());
            assertEquals(-1, tracker.known(0));
            buffer.clear();
            var source = new Pair();
            source.a.set(30);
            source.b.set(40);
            source.writeDelta(buffer, new NetworkComponent.DeltaTracker());
            destination.readDelta(buffer, tracker);
            assertEquals(30, destination.a.get());
            assertEquals(40, destination.b.get());
        } finally {
            buffer.release();
        }
    }

    /**
     * Recursive user codecs using the same tracker must lease independent snapshots instead of
     * overwriting outer data.
     */
    @Test
    void recursiveEncode_sameTracker_keepsOuterAndInnerCapturesIndependent() {
        var source = new Pair();
        var tracker = new NetworkComponent.DeltaTracker();
        FriendlyByteBuf outer = buffer();
        FriendlyByteBuf inner = buffer();
        try {
            source.a.set(10);
            source.b.set(20);
            source.onWrite =
                    () -> {
                        source.a.set(30);
                        source.b.set(40);
                        source.writeDelta(inner, tracker);
                    };
            source.writeDelta(outer, tracker);
            var old = new Pair();
            var newer = new Pair();
            old.readDelta(outer, new NetworkComponent.DeltaTracker());
            newer.readDelta(inner, new NetworkComponent.DeltaTracker());
            assertEquals(10, old.a.get());
            assertEquals(20, old.b.get());
            assertEquals(30, newer.a.get());
            assertEquals(40, newer.b.get());
            outer.clear();
            assertTrue(source.writeDelta(outer, tracker));
        } finally {
            outer.release();
            inner.release();
        }
    }

    /**
     * Sparse cleanup must clear captured references and duplicate markers after a decoder fails midway.
     * @throws Exception if private staging cannot be inspected for retained references
     */
    @Test
    void failedDecode_releaseClearsEveryPopulatedStagingReference() throws Exception {
        var target = new Pair();
        var tracker = new NetworkComponent.DeltaTracker();
        FriendlyByteBuf wire = buffer();
        try {
            wire.writeVarInt(2);
            wire.writeVarInt(0);
            wire.writeVarLong(1);
            wire.writeVarInt(1357);
            wire.writeVarInt(1);
            wire.writeVarLong(1);
            assertThrows(IndexOutOfBoundsException.class, () -> target.readDelta(wire, tracker));
            var available = tracker.getClass().getDeclaredField("available");
            available.setAccessible(true);
            Object staging = available.get(tracker);
            assertNotNull(staging);
            var values = staging.getClass().getDeclaredField("values");
            values.setAccessible(true);
            for (Object value : (Object[]) values.get(staging)) assertNull(value);
            var seen = staging.getClass().getDeclaredField("seen");
            seen.setAccessible(true);
            for (boolean present : (boolean[]) seen.get(staging)) assertFalse(present);
            assertEquals(-1, tracker.known(0));
            assertEquals(0, target.a.get());
        } finally {
            wire.release();
        }
    }

    /**
     * Allocates an exclusively owned packet fixture.
     *
     * @return buffer that the test must release
     */
    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /**
     * Two fields; the last encoder can recursively submit or fail a packet after all snapshots are
     * captured.
     */
    private static final class Pair extends NetworkComponent {
        /**
         * One-shot user encoder callback.
         */
        Runnable onWrite;

        /**
         * First value, captured before the callback executes.
         */
        final TrackedField<Integer> a = tracked("a", FieldCodecs.intValue(), 0);

        /**
         * Last value using a callback-enabled codec.
         */
        final TrackedField<Integer> b =
                tracked(
                        "b",
                        FieldCodec.<Integer>builder()
                                .schema("test:lease")
                                .nbt(
                                        (tag, key, value) -> tag.putInt(key, value),
                                        (tag, key, fallback) -> fallback)
                                .network(
                                        (wire, value) -> {
                                            Runnable callback = onWrite;
                                            onWrite = null;
                                            if (callback != null) callback.run();
                                            wire.writeVarInt(value);
                                        },
                                        FriendlyByteBuf::readVarInt)
                                .build(),
                        0);
    }
}
