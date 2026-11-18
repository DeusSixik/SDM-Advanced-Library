package dev.sixik.sdmal.core.network;

import io.netty.buffer.Unpooled;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Bounded native chunks with connection isolation, fixed deadlines and reserved-byte accounting.
 */
final class NetworkFragmentation {
    /**
     * Reserved marker distinct from ordinary and request frames.
     */
    static final int MAGIC = 0x7A;
    /**
     * Fragment wire version.
     */
    private static final int VERSION = 1;
    /**
     * Two marker bytes, transfer id and three 32-bit metadata fields.
     */
    static final int HEADER = 22;
    /**
     * Payload bytes per full chunk, fitting inside the native 30-KiB limit.
     */
    static final int CHUNK = NetworkRuntime.MAX_FRAME_BYTES - HEADER;
    /**
     * Process-unique transfer ids independent of request correlation ids.
     */
    private static final AtomicLong IDS = new AtomicLong();
    /**
     * Production receiver; tests use an independent receiver and clock.
     */
    private static final Receiver RECEIVER = new Receiver(System::nanoTime);

    static {
        NetworkMaintenance.schedule(RECEIVER::expire);
    }

    /**
     * Prevents utility construction.
     */
    private NetworkFragmentation() {
    }

    /**
     * @param frame direct native frame @return whether its marker reserves the fragment protocol
     */
    static boolean is(byte[] frame) {
        return frame.length > 0 && (frame[0] & 255) == MAGIC;
    }

    /**
     * Encodes a logical message into native-sized frames without wrapping small messages.
     *
     * @param frame  complete immutable logical message
     * @param sender native frame consumer; may retain each frame
     */
    static void send(byte[] frame, Consumer<byte[]> sender) {
        if (frame.length == 0 || frame.length > NetworkRuntime.MAX_LOGICAL_BYTES)
            throw new IllegalArgumentException("Invalid logical message size: " + frame.length);
        if (frame.length <= NetworkRuntime.MAX_FRAME_BYTES) {
            sender.accept(frame);
            return;
        }
        long id = IDS.incrementAndGet();
        int count = (frame.length + CHUNK - 1) / CHUNK;
        for (int index = 0; index < count; index++) {
            int offset = index * CHUNK;
            int length = Math.min(CHUNK, frame.length - offset);
            var buffer = new FriendlyByteBuf(Unpooled.buffer(HEADER + length, HEADER + length));
            try {
                buffer.writeByte(MAGIC).writeByte(VERSION).writeLong(id);
                buffer.writeInt(index).writeInt(count).writeInt(frame.length);
                buffer.writeBytes(frame, offset, length);
                var part = new byte[HEADER + length];
                buffer.readBytes(part);
                sender.accept(part);
            } finally {
                buffer.release();
            }
        }
    }

    /**
     * @param frame native frame @param peer authenticated endpoint @return completed logical message, or null
     */
    static byte[] accept(byte[] frame, NetworkPeer peer) {
        return RECEIVER.accept(frame, peer);
    }

    /**
     * Receiver with injectable clock for deterministic deadline and memory-budget tests.
     */
    static final class Receiver {
        /**
         * Absolute lifetime from first chunk; duplicates never prolong it.
         */
        static final long TIMEOUT = TimeUnit.SECONDS.toNanos(30);
        /**
         * Maximum reserved logical bytes process-wide.
         */
        static final long GLOBAL_BYTES = 128L * 1024 * 1024;
        /**
         * Maximum reserved bytes per authenticated connection endpoint.
         */
        static final long PEER_BYTES = 64L * 1024 * 1024;
        /**
         * Maximum outstanding transfers process-wide.
         */
        static final int GLOBAL_TRANSFERS = 128;
        /**
         * Maximum outstanding transfers per endpoint.
         */
        static final int PEER_TRANSFERS = 16;
        /**
         * Completed ids bounded in count/age without retaining payload bytes.
         */
        private final LinkedHashMap<Key, Long> completed = new LinkedHashMap<>();
        /**
         * Outstanding transfer buffers, guarded by this monitor.
         */
        private final Map<Key, Transfer> transfers = new HashMap<>();
        /**
         * Reservation counters by peer endpoint.
         */
        private final Map<PeerKey, Usage> peers = new HashMap<>();
        /**
         * Monotonic nanosecond clock.
         */
        private final LongSupplier clock;
        /**
         * Total reserved payload bytes.
         */
        private long reserved;

        /**
         * @param clock monotonic nanosecond source
         */
        Receiver(LongSupplier clock) {
            this.clock = clock;
        }

        /**
         * Validates offsets/quotas before reserving memory and copies directly into the final array.
         *
         * @param frame native fragment or small logical frame
         * @param peer  receiving connection
         * @return completed logical frame or null for incomplete/replayed messages
         */
        synchronized byte[] accept(byte[] frame, NetworkPeer peer) {
            if (frame.length == 0 || frame.length > NetworkRuntime.MAX_FRAME_BYTES)
                throw new IllegalArgumentException("Invalid native frame size");
            if (!is(frame)) return frame;
            if (frame.length < HEADER) throw new IllegalArgumentException("Truncated fragment header");
            long now = clock.getAsLong();
            expire(now);
            var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
            Key key = null;
            try {
                buffer.readUnsignedByte();
                if (buffer.readUnsignedByte() != VERSION)
                    throw new IllegalArgumentException("Unsupported fragment version");
                long id = buffer.readLong();
                key = new Key(new PeerKey(peer.side, peer.identity), id);
                int index = buffer.readInt();
                int count = buffer.readInt();
                int total = buffer.readInt();
                if (total <= NetworkRuntime.MAX_FRAME_BYTES || total > NetworkRuntime.MAX_LOGICAL_BYTES)
                    throw new IllegalArgumentException("Fragment total size out of bounds");
                int expectedCount = (total + CHUNK - 1) / CHUNK;
                if (count != expectedCount || index < 0 || index >= count)
                    throw new IllegalArgumentException("Invalid fragment index/count");
                int offset = index * CHUNK;
                int length = Math.min(CHUNK, total - offset);
                if (buffer.readableBytes() != length)
                    throw new IllegalArgumentException("Wrong fragment payload length");
                if (completed.containsKey(key)) return null;
                var transfer = transfers.get(key);
                if (transfer == null) {
                    var usage = peers.get(key.peer());
                    if (transfers.size() >= GLOBAL_TRANSFERS || reserved + total > GLOBAL_BYTES
                            || usage != null && (usage.count >= PEER_TRANSFERS || usage.bytes + total > PEER_BYTES))
                        throw new IllegalArgumentException("Fragment reassembly budget exceeded");
                    transfer = new Transfer(total, count, now, peer);
                    transfers.put(key, transfer);
                    if (usage == null) {
                        usage = new Usage();
                        peers.put(key.peer(), usage);
                    }
                    usage.count++;
                    usage.bytes += total;
                    reserved += total;
                }
                if (transfer.data.length != total || transfer.count != count)
                    throw new IllegalArgumentException("Fragment metadata changed during transfer");
                if (transfer.received.get(index)) {
                    for (int at = 0; at < length; at++)
                        if (transfer.data[offset + at] != buffer.getByte(buffer.readerIndex() + at))
                            throw new IllegalArgumentException("Conflicting duplicate fragment");
                    return null;
                }
                buffer.readBytes(transfer.data, offset, length);
                transfer.received.set(index);
                transfer.receivedCount++;
                if (transfer.receivedCount != count) return null;
                remove(key, transfer);
                completed.put(key, now);
                if (completed.size() > 1024) {
                    var iterator = completed.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
                return transfer.data;
            } catch (RuntimeException failure) {
                if (key != null) {
                    var transfer = transfers.get(key);
                    if (transfer != null) remove(key, transfer);
                }
                throw failure;
            } finally {
                buffer.release();
            }
        }

        /**
         * Removes expired/disconnected transfers, releasing reservations.
         */
        synchronized void expire() {
            expire(clock.getAsLong());
        }

        /**
         * @param now monotonic nanosecond time
         */
        private void expire(long now) {
            var iterator = transfers.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var transfer = entry.getValue();
                if (now - transfer.started < TIMEOUT && transfer.peer.connected()) continue;
                iterator.remove();
                release(entry.getKey(), transfer);
            }
            completed.entrySet().removeIf(entry -> now - entry.getValue() >= TIMEOUT);
        }

        /**
         * @param key removed transfer id @param transfer its allocated storage
         */
        private void remove(Key key, Transfer transfer) {
            transfers.remove(key);
            release(key, transfer);
        }

        /**
         * @param key removed transfer id @param transfer released reservation
         */
        private void release(Key key, Transfer transfer) {
            reserved -= transfer.data.length;
            var usage = peers.get(key.peer());
            usage.count--;
            usage.bytes -= transfer.data.length;
            if (usage.count == 0) peers.remove(key.peer());
        }

        /**
         * @return current reserved payload bytes
         */
        synchronized long reservedBytes() {
            return reserved;
        }

        /**
         * @return outstanding transfer count
         */
        synchronized int size() {
            return transfers.size();
        }
    }

    /**
     * Per-connection endpoint; integrated client and server sides remain separate.
     */
    private record PeerKey(dev.sixik.sdmal.api.network.Side side, Object identity) {
    }

    /**
     * Connection-scoped transfer id.
     */
    private record Key(PeerKey peer, long id) {
    }

    /**
     * Reservation totals for one peer.
     */
    private static final class Usage {
        /**
         * Reserved payload bytes.
         */
        long bytes;
        /**
         * Outstanding transfer count.
         */
        int count;
    }

    /**
     * One final-size assembly buffer and a bit per received fragment.
     */
    private static final class Transfer {
        /**
         * Final assembly buffer without a second full-payload copy.
         */
        final byte[] data;
        /**
         * Expected fragment count.
         */
        final int count;
        /**
         * Received chunk indices.
         */
        final BitSet received;
        /**
         * Absolute first-chunk time, nanoseconds.
         */
        final long started;
        /**
         * Original endpoint for disconnect cleanup.
         */
        final NetworkPeer peer;
        /**
         * Distinct received chunk count.
         */
        int receivedCount;

        /**
         * @param total message bytes @param count fragment count @param started first chunk time @param peer connection
         */
        Transfer(int total, int count, long started, NetworkPeer peer) {
            this.data = new byte[total];
            this.count = count;
            this.received = new BitSet(count);
            this.started = started;
            this.peer = peer;
        }
    }
}

