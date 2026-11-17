package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkPacket;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;

import io.netty.buffer.Unpooled;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;

import org.jetbrains.annotations.ApiStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Immutable logical packet registry behind the loader's single bounded play channel.
 */
@ApiStatus.Internal
public final class PacketRegistry {
    /**
     * Processor index path; each dependency contributes its own resource.
     */
    public static final String INDEX = "META-INF/sdmal/network-packets";

    /**
     * Maximum complete logical frame, below the legacy 32767-byte C2S payload limit.
     */
    public static final int MAX_BYTES = 30 * 1024;

    /**
     * Framing version independent of individual field schemas.
     */
    private static final int VERSION = 1;

    /**
     * Id lookup independent of mod iteration or compiler output order.
     */
    private final Map<String, Entry> byId = new Object2ObjectOpenHashMap<>();

    /**
     * Exact concrete send types; subclasses require their own annotation and index entry.
     */
    private final Map<Class<?>, Entry> byClass = new Object2ObjectOpenHashMap<>();

    /**
     * Validates a complete set of discovered types without invoking packet constructors.
     *
     * @param types indexed concrete packet classes
     */
    public PacketRegistry(Collection<Class<? extends Packet>> types) {
        for (var type : types) {
            var annotation = type.getDeclaredAnnotation(NetworkPacket.class);
            if (annotation == null
                    || !annotation.id().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || annotation.id().length() > 256)
                throw new IllegalStateException("Invalid @NetworkPacket id on " + type.getName());
            if (!Packet.class.isAssignableFrom(type)
                    || Modifier.isAbstract(type.getModifiers())
                    || type.isInterface())
                throw new IllegalStateException("Expected concrete Packet: " + type.getName());
            var entry = new Entry(annotation.id(), annotation.side(), type);
            var previous = byId.putIfAbsent(entry.id, entry);
            if (previous != null && previous.type != type)
                throw new IllegalStateException(
                        "Duplicate packet id "
                                + entry.id
                                + ": "
                                + previous.type.getName()
                                + " and "
                                + type.getName());
            byClass.put(type, entry);
        }
    }

    /**
     * Reads all compiler-produced dependency indices without initializing the indexed classes.
     *
     * @param loader mod class loader
     * @return validated immutable registry
     */
    public static PacketRegistry discover(ClassLoader loader) {
        var types = new LinkedHashSet<Class<? extends Packet>>();
        try {
            var resources = loader.getResources(INDEX);
            while (resources.hasMoreElements()) {
                var resource = resources.nextElement();
                try (var reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             resource.openStream(), StandardCharsets.UTF_8))) {
                    String name;
                    while ((name = reader.readLine()) != null) {
                        name = name.strip();
                        if (name.isEmpty() || name.startsWith("#")) continue;
                        var type = Class.forName(name, false, loader).asSubclass(Packet.class);
                        types.add(type);
                    }
                }
            }
            return new PacketRegistry(types);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException(
                    "Cannot discover network packets from " + INDEX, failure);
        }
    }

    /**
     * Snapshots one packet into a bounded binary frame; no NBT is involved.
     *
     * @param packet source message
     * @param side   expected receiving side
     * @return owned immutable-by-convention byte snapshot
     */
    public byte[] encode(Packet packet, Side side) {
        var entry = requireEntry(packet, side);
        var buffer = new FriendlyByteBuf(Unpooled.buffer(256, MAX_BYTES));
        try {
            buffer.writeByte(VERSION);
            buffer.writeUtf(entry.id, 256);
            entry.codec().write(buffer, packet);
            byte[] frame = new byte[buffer.readableBytes()];
            buffer.readBytes(frame);
            return frame;
        } finally {
            buffer.release();
        }
    }

    /**
     * Checks a send even when its recipient group is empty, without encoding unused payload data.
     *
     * @param packet exact indexed packet instance
     * @param side   required receiving side
     * @throws IllegalStateException if the registration or direction is invalid
     */
    public void validateDirection(Packet packet, Side side) {
        requireEntry(packet, side);
    }

    /**
     * Resolves an exact send type and enforces its logical receiving direction.
     *
     * @param packet source instance
     * @param side   expected receiving side
     * @return registered packet entry
     * @throws IllegalStateException if the packet is unindexed or targets another side
     */
    private Entry requireEntry(Packet packet, Side side) {
        var entry = byClass.get(packet.getClass());
        if (entry == null)
            throw new IllegalStateException(
                    "Packet is not indexed: "
                            + packet.getClass().getName()
                            + "; enable the SDMAL annotation processor");
        entry.requireSide(side);
        return entry;
    }

    /**
     * Decodes only registered, direction-correct, compatible messages and rejects trailing bytes.
     *
     * @param frame bounded received bytes
     * @param side  receiving side supplied by the loader
     * @return fully decoded message
     */
    public Packet decode(byte[] frame, Side side) {
        if (frame.length == 0 || frame.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid packet frame size: " + frame.length);
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        try {
            if (buffer.readUnsignedByte() != VERSION)
                throw new IllegalArgumentException("Unsupported SDMAL packet protocol");
            String id = buffer.readUtf(256);
            var entry = byId.get(id);
            if (entry == null) throw new IllegalArgumentException("Unknown packet id: " + id);
            entry.requireSide(side);
            var packet = entry.codec().read(buffer);
            if (buffer.isReadable())
                throw new IllegalArgumentException("Trailing bytes in packet " + id);
            return packet;
        } finally {
            buffer.release();
        }
    }

    /**
     * @return number of indexed logical packet types
     */
    public int size() {
        return byId.size();
    }

    /**
     * Metadata loaded eagerly; formats freeze lazily so mods can register custom adapters at setup.
     */
    private static final class Entry {
        /**
         * Stable logical identifier.
         */
        private final String id;

        /**
         * Required receiving side.
         */
        private final Side side;

        /**
         * Exact concrete packet type.
         */
        private final Class<? extends Packet> type;

        /**
         * Validated immutable format, published after first complete resolution.
         */
        private volatile EcsNetworkSerializer<Packet> codec;

        /**
         * Captures annotation metadata.
         *
         * @param id   logical identifier
         * @param side receiving side
         * @param type exact packet class
         */
        private Entry(String id, Side side, Class<? extends Packet> type) {
            this.id = id;
            this.side = side;
            this.type = type;
        }

        /**
         * Resolves and caches a complete format.
         *
         * @return shared thread-safe field plan
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private EcsNetworkSerializer<Packet> codec() {
            var result = codec;
            if (result != null) return result;
            synchronized (this) {
                if (codec == null)
                    codec = (EcsNetworkSerializer) NetworkSerialization.packetCodec(type);
                return codec;
            }
        }

        /**
         * Rejects attempts to execute a packet on the opposite logical side.
         *
         * @param actual loader-supplied receiving side
         */
        private void requireSide(Side actual) {
            if (side != actual)
                throw new IllegalStateException(
                        "Packet " + id + " receives on " + side + ", not " + actual);
        }
    }
}
