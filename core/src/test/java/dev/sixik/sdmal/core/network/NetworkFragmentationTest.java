package dev.sixik.sdmal.core.network;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.network.Side;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Deterministic chunk integrity, replay, connection isolation, deadlines and reservation budgets. */
class NetworkFragmentationTest {
    /** Small frames retain their identity and never allocate reassembly storage. */
    @Test void send_smallFrame_passesOriginalArrayDirectly() {
        var frame = new byte[] {1, 2, 3};
        var chunks = new ArrayList<byte[]>(); NetworkFragmentation.send(frame, chunks::add);
        assertEquals(1, chunks.size()); assertSame(frame, chunks.get(0));
        var receiver = new NetworkFragmentation.Receiver(() -> 0);
        assertSame(frame, receiver.accept(frame, peer())); assertEquals(0, receiver.reservedBytes());
    }

    /** Out-of-order chunks and exact duplicates assemble once without changing any payload byte. */
    @Test void accept_outOfOrderDuplicates_reassemblesAndIgnoresReplay() {
        var logical = new byte[100_000];
        for (int index = 0; index < logical.length; index++) logical[index] = (byte) (index * 17);
        var chunks = new ArrayList<byte[]>(); NetworkFragmentation.send(logical, chunks::add);
        var receiver = new NetworkFragmentation.Receiver(() -> 0); var peer = peer();
        assertNull(receiver.accept(chunks.get(2), peer)); assertNull(receiver.accept(chunks.get(2), peer));
        byte[] result = null;
        for (int index = chunks.size() - 1; index >= 0; index--) {
            var completed = receiver.accept(chunks.get(index), peer);
            if (completed != null) result = completed;
        }
        assertArrayEquals(logical, result);
        assertEquals(0, receiver.reservedBytes()); assertEquals(0, receiver.size());
        for (var chunk : chunks) assertNull(receiver.accept(chunk, peer));
        assertEquals(0, receiver.reservedBytes());
    }

    /** Integrated client/server endpoints with the same connection object never share assemblies. */
    @Test void accept_sameTransferIdOnDifferentEndpoints_keepsAssembliesIndependent() {
        var chunks = new ArrayList<byte[]>(); NetworkFragmentation.send(new byte[40_000], chunks::add);
        var receiver = new NetworkFragmentation.Receiver(() -> 0); Object connection = new Object();
        var server = new NetworkPeer(Side.SERVER, connection, () -> true, frame -> {});
        var client = new NetworkPeer(Side.CLIENT, connection, () -> true, frame -> {});
        assertNull(receiver.accept(chunks.get(0), server));
        assertNull(receiver.accept(chunks.get(1), client));
        assertEquals(2, receiver.size());
        assertNotNull(receiver.accept(chunks.get(1), server));
        assertNotNull(receiver.accept(chunks.get(0), client));
        assertEquals(0, receiver.reservedBytes());
    }

    /** Invalid dimensions are rejected before reserving a final-size payload array.
     * @param index proposed chunk index @param count proposed chunk count @param total declared logical bytes */
    @ParameterizedTest
    @CsvSource({"-1,2,40000", "2,2,40000", "0,0,40000", "0,1,40000", "0,2,-1", "0,2,30720", "0,2,33554433"})
    void accept_invalidHeader_rejectsBeforeReservation(int index, int count, int total) {
        var receiver = new NetworkFragmentation.Receiver(() -> 0);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(chunk(1, index, count, total, 0), peer()));
        assertEquals(0, receiver.reservedBytes()); assertEquals(0, receiver.size());
    }

    /** Changed metadata or conflicting duplicate payload aborts and releases its original reservation. */
    @Test void accept_conflictingFragments_releasesAbortedTransfer() {
        var receiver = new NetworkFragmentation.Receiver(() -> 0); var peer = peer();
        var first = first(1, 40_000);
        assertNull(receiver.accept(first, peer));
        var changed = Arrays.copyOf(first, first.length); changed[changed.length - 1] = 1;
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(changed, peer));
        assertEquals(0, receiver.reservedBytes());
        assertNull(receiver.accept(first, peer));
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(first(1, 40_001), peer));
        assertEquals(0, receiver.reservedBytes());
    }

    /** Duplicate traffic never extends the original absolute deadline. */
    @Test void expire_repeatedDuplicate_doesNotExtendDeadline() {
        var clock = new AtomicLong(); var receiver = new NetworkFragmentation.Receiver(clock::get);
        var peer = peer(); var first = first(1, 40_000);
        receiver.accept(first, peer);
        clock.set(NetworkFragmentation.Receiver.TIMEOUT - 1); receiver.accept(first, peer);
        assertEquals(40_000, receiver.reservedBytes());
        clock.incrementAndGet(); receiver.expire();
        assertEquals(0, receiver.size()); assertEquals(0, receiver.reservedBytes());
    }

    /** Disconnect cleanup releases buffers without waiting for the transfer timeout. */
    @Test void expire_disconnectedPeer_releasesReservation() {
        var connected = new AtomicBoolean(true);
        var peer = new NetworkPeer(Side.SERVER, new Object(), connected::get, frame -> {});
        var receiver = new NetworkFragmentation.Receiver(() -> 0);
        receiver.accept(first(1, 40_000), peer); connected.set(false); receiver.expire();
        assertEquals(0, receiver.size()); assertEquals(0, receiver.reservedBytes());
    }

    /** One peer cannot allocate beyond its aggregate byte budget using several valid headers. */
    @Test void accept_peerByteBudget_rejectsBeforeAdditionalAllocation() {
        var receiver = new NetworkFragmentation.Receiver(() -> 0); var peer = peer();
        receiver.accept(first(1, NetworkRuntime.MAX_LOGICAL_BYTES), peer);
        receiver.accept(first(2, NetworkRuntime.MAX_LOGICAL_BYTES), peer);
        assertEquals(NetworkFragmentation.Receiver.PEER_BYTES, receiver.reservedBytes());
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(first(3, 40_000), peer));
        assertEquals(NetworkFragmentation.Receiver.PEER_BYTES, receiver.reservedBytes());
        assertEquals(2, receiver.size());
    }

    /** Total reservation across different authenticated peers remains bounded. */
    @Test void accept_globalByteBudget_rejectsBeforeAdditionalAllocation() {
        var receiver = new NetworkFragmentation.Receiver(() -> 0);
        for (int index = 0; index < 4; index++) receiver.accept(first(index, NetworkRuntime.MAX_LOGICAL_BYTES), peer());
        assertEquals(NetworkFragmentation.Receiver.GLOBAL_BYTES, receiver.reservedBytes());
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(first(5, 40_000), peer()));
        assertEquals(NetworkFragmentation.Receiver.GLOBAL_BYTES, receiver.reservedBytes()); assertEquals(4, receiver.size());
    }

    /** Many small outstanding transfers also respect per-peer and global count budgets. */
    @Test void accept_transferCountBudget_rejectsUntilExpiredReservationsRelease() {
        var clock = new AtomicLong(); var receiver = new NetworkFragmentation.Receiver(clock::get);
        var firstPeer = peer();
        for (int index = 0; index < 16; index++) receiver.accept(first(index, 40_000), firstPeer);
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(first(17, 40_000), firstPeer));
        for (int index = 16; index < 128; index++) receiver.accept(first(index, 40_000), peer());
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(first(129, 40_000), peer()));
        clock.set(NetworkFragmentation.Receiver.TIMEOUT); receiver.expire();
        assertEquals(0, receiver.size()); assertEquals(0, receiver.reservedBytes());
        assertNull(receiver.accept(first(130, 40_000), firstPeer));
    }

    /** Native framing cannot be bypassed by sending an oversized direct frame or a truncated header. */
    @Test void accept_invalidNativeSizeOrVersion_rejects() {
        var receiver = new NetworkFragmentation.Receiver(() -> 0); var peer = peer();
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(new byte[0], peer));
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(new byte[NetworkRuntime.MAX_FRAME_BYTES + 1], peer));
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(new byte[] {(byte) NetworkFragmentation.MAGIC}, peer));
        var badVersion = first(1, 40_000); badVersion[1] = 99;
        assertThrows(IllegalArgumentException.class, () -> receiver.accept(badVersion, peer));
        assertEquals(0, receiver.reservedBytes());
    }

    /** @return fresh authenticated endpoint */
    private static NetworkPeer peer() { return new NetworkPeer(Side.SERVER, new Object(), () -> true, frame -> {}); }
    /** @param id transfer id @param total declared logical bytes @return valid first chunk without allocating the logical message */
    private static byte[] first(long id, int total) {
        return chunk(id, 0, (total + NetworkFragmentation.CHUNK - 1) / NetworkFragmentation.CHUNK,
                total, NetworkFragmentation.CHUNK);
    }
    /** @param id transfer id @param index chunk index @param count chunk count @param total logical bytes
     * @param payload native payload bytes @return crafted native frame */
    private static byte[] chunk(long id, int index, int count, int total, int payload) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer(NetworkFragmentation.HEADER + payload));
        try {
            buffer.writeByte(NetworkFragmentation.MAGIC).writeByte(1).writeLong(id);
            buffer.writeInt(index).writeInt(count).writeInt(total); buffer.writeZero(payload);
            var frame = new byte[buffer.readableBytes()]; buffer.readBytes(frame); return frame;
        } finally { buffer.release(); }
    }
}
