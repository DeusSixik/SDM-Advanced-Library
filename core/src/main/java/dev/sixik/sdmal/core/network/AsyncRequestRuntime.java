package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.network.*;
import dev.sixik.sdmal.api.network.annotations.NetworkRequest;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;
import io.netty.buffer.Unpooled;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Typed request correlation bound to exact logical connections, including integrated servers.
 */
@ApiStatus.Internal
public final class AsyncRequestRuntime {
    /**
     * Compiler-generated resource-only request index.
     */
    public static final String INDEX = "META-INF/sdmal/network-requests";
    /**
     * Maximum outstanding requests per process.
     */
    private static final int MAX_PENDING = 4096;
    /**
     * Maximum outstanding outgoing/incoming requests per connection endpoint.
     */
    private static final int MAX_PER_PEER = 128;
    /**
     * Process-wide correlation sequence, distinct from fragment transfer ids.
     */
    private static final AtomicLong IDS = new AtomicLong();
    /**
     * Caller futures; each entry retains only its original connection until completion/deadline.
     */
    private static final ConcurrentHashMap<Long, Pending> PENDING = new ConcurrentHashMap<>();
    /**
     * Receiving handlers which may finish asynchronously.
     */
    private static final ConcurrentHashMap<IncomingKey, Responder> INCOMING = new ConcurrentHashMap<>();
    /**
     * Bounded replay ids without decoded messages or response bodies.
     */
    private static final Object2ObjectLinkedOpenHashMap<IncomingKey, Long> COMPLETED = new Object2ObjectLinkedOpenHashMap<>();
    /**
     * Indexed metadata by stable request id.
     */
    private static volatile Map<String, Entry> byId = it.unimi.dsi.fastutil.objects.Object2ObjectMaps.emptyMap();
    /**
     * Indexed metadata by exact request class.
     */
    private static volatile Map<Class<?>, Entry> byClass = it.unimi.dsi.fastutil.objects.Object2ObjectMaps.emptyMap();
    /**
     * Prevents repeating discovery for an empty modpack.
     */
    private static boolean installed;

    static {
        NetworkMaintenance.schedule(AsyncRequestRuntime::expireConnections);
    }

    /**
     * Prevents utility construction.
     */
    private AsyncRequestRuntime() {
    }

    /**
     * Discovers request classes without constructing or initializing their implementations.
     *
     * @param loader mod classloader
     */
    public static synchronized void install(ClassLoader loader) {
        if (installed) return;
        var ids = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, Entry>();
        var classes = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Class<?>, Entry>();
        var types = new ObjectLinkedOpenHashSet<Class<?>>();
        try {
            var resources = loader.getResources(INDEX);
            while (resources.hasMoreElements()) {
                try (var reader = new BufferedReader(new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    String name;
                    while ((name = reader.readLine()) != null) {
                        name = name.strip();
                        if (!name.isEmpty() && !name.startsWith("#")) types.add(Class.forName(name, false, loader));
                    }
                }
            }
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("Cannot discover automatic network requests", failure);
        }
        for (var type : types) {
            var entry = new Entry(type);
            var previous = ids.putIfAbsent(entry.id, entry);
            if (previous != null) throw new IllegalStateException("Duplicate async request id: " + entry.id);
            classes.put(type, entry);
        }
        byId = it.unimi.dsi.fastutil.objects.Object2ObjectMaps.unmodifiable(ids);
        byClass = it.unimi.dsi.fastutil.objects.Object2ObjectMaps.unmodifiable(classes);
        installed = true;
    }

    /**
     * @param request client-originated request @param <R> result type @return correlated result future
     */
    @SuppressWarnings("unchecked")
    public static <R> CompletableFuture<R> sendToServer(AsyncRequest<R> request) {
        var entry = require(request, Side.SERVER);
        return (CompletableFuture<R>) (CompletableFuture<?>) send(request, entry, NetworkRuntime.serverPeer());
    }

    /**
     * @param request server-originated request @param player client recipient @param <R> result type @return correlated future
     */
    @SuppressWarnings("unchecked")
    public static <R> CompletableFuture<R> sendToPlayer(AsyncRequest<R> request, Player player) {
        if (!(player instanceof ServerPlayer recipient))
            throw new IllegalArgumentException("Recipient must be a ServerPlayer");
        var entry = require(request, Side.CLIENT);
        return (CompletableFuture<R>) (CompletableFuture<?>) send(request, entry, NetworkRuntime.playerPeer(recipient));
    }

    /**
     * Processes a complete envelope on the receiving game executor.
     *
     * @param frame    reassembled logical envelope
     * @param peer     authenticated original connection
     * @param player   current connection player
     * @param executor game executor used for deferred replies
     * @param reject   malformed input callback
     */
    static void receive(byte[] frame, NetworkPeer peer, Player player, Executor executor, Consumer<String> reject) {
        var envelope = AsyncEnvelope.decode(frame);
        if (!envelope.request()) {
            handleResponse(envelope, peer, reject);
            return;
        }
        var entry = byId.get(envelope.subject());
        if (entry == null) {
            sendFailure(envelope, peer, "Unknown request id");
            return;
        }
        if (entry.side != peer.side) {
            reject.accept("Async request arrived on the wrong side");
            return;
        }
        var key = new IncomingKey(peer.side, peer.identity, envelope.id());
        Responder responder;
        synchronized (INCOMING) {
            synchronized (COMPLETED) {
                if (COMPLETED.containsKey(key)) return;
            }
            if (INCOMING.containsKey(key)) return;
            if (INCOMING.size() >= MAX_PENDING || incomingCount(peer) >= MAX_PER_PEER) {
                sendFailure(envelope, peer, "Too many outstanding requests");
                return;
            }
            responder = new Responder(entry, key, peer, executor);
            INCOMING.put(key, responder);
        }
        try {
            Object request = decode(entry.requestCodec(), envelope);
            @SuppressWarnings("unchecked") var handler = (AsyncRequest<Object>) request;
            handler.handle(new PacketContext(peer.side, player, executor), responder);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            SDMAdvancedLibrary.LOGGER.error("Async request handler failed: {}", entry.id, failure);
            responder.fail("Remote request handler failed");
        }
    }

    /**
     * @param envelope parsed response @param peer authenticated source @param reject malformed response callback
     */
    private static void handleResponse(AsyncEnvelope.Decoded envelope, NetworkPeer peer, Consumer<String> reject) {
        var pending = PENDING.get(envelope.id());
        if (pending == null) return;
        if (!pending.peer.matches(peer) || !pending.entry.id.equals(envelope.subject())) {
            reject.accept("Async response does not match the requested connection/id");
            return;
        }
        if (!pending.peer.connected()) {
            pending.future.completeExceptionally(new IllegalStateException("Play connection closed"));
            return;
        }
        if (!PENDING.remove(envelope.id(), pending)) return;
        pending.cancelTimeout();
        if (!envelope.success()) {
            if (envelope.length() != 0) {
                pending.future.completeExceptionally(new IllegalArgumentException("Failed response has a body"));
                return;
            }
            pending.future.completeExceptionally(new RemoteRequestException(envelope.error()));
            return;
        }
        try {
            if (pending.entry.responseClass == Void.class) {
                if (envelope.length() != 0) throw new IllegalArgumentException("Void response must have no body");
                pending.future.complete(null);
                return;
            }
            pending.future.complete(decode(pending.entry.responseCodec(), envelope));
        } catch (Throwable failure) {
            rethrowFatal(failure);
            pending.future.completeExceptionally(failure);
        }
    }

    /**
     * @param request source message @param entry indexed metadata @param peer exact target @return result future
     */
    private static CompletableFuture<Object> send(AsyncRequest<?> request, Entry entry, NetworkPeer peer) {
        var future = new CompletableFuture<Object>();
        if (!peer.connected()) {
            future.completeExceptionally(new IllegalStateException("Play connection closed"));
            return future;
        }
        long id = IDS.incrementAndGet();
        var pending = new Pending(entry, peer, future);
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING || pendingCount(peer) >= MAX_PER_PEER) {
                future.completeExceptionally(new IllegalStateException("Too many outstanding requests"));
                return future;
            }
            PENDING.put(id, pending);
        }
        future.whenComplete((value, failure) -> {
            PENDING.remove(id, pending);
            pending.cancelTimeout();
        });
        pending.timeout = NetworkMaintenance.TIMER.schedule(() ->
                        future.completeExceptionally(new TimeoutException("Async request timed out: " + entry.id)),
                entry.timeoutMillis, TimeUnit.MILLISECONDS);
        if (future.isDone()) pending.cancelTimeout();
        try {
            byte[] body = encode(entry.requestCodec(), request);
            byte[] envelope = AsyncEnvelope.encode(id, true, entry.id, true, null, body);
            if (!future.isDone()) peer.send(envelope);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            future.completeExceptionally(failure);
        }
        return future;
    }

    /**
     * @param peer connection endpoint @return outstanding outgoing requests on it
     */
    private static int pendingCount(NetworkPeer peer) {
        int count = 0;
        for (var pending : PENDING.values()) if (pending.peer.matches(peer)) count++;
        return count;
    }

    /**
     * @param peer connection endpoint @return outstanding receiving handlers on it
     */
    private static int incomingCount(NetworkPeer peer) {
        int count = 0;
        for (var responder : INCOMING.values()) if (responder.peer.matches(peer)) count++;
        return count;
    }

    /**
     * Clears disconnected requests and expired/uncompleted receiving handlers; caller futures release their timers.
     */
    static void expireConnections() {
        long now = System.nanoTime();
        for (var pending : PENDING.values())
            if (!pending.peer.connected())
                pending.future.completeExceptionally(new IllegalStateException("Play connection closed"));
        for (var responder : INCOMING.values()) {
            if (now - responder.started < TimeUnit.MILLISECONDS.toNanos(responder.entry.timeoutMillis) && responder.peer.connected())
                continue;
            responder.finish();
        }
        synchronized (COMPLETED) {
            COMPLETED.entrySet().removeIf(entry -> now - entry.getValue() >= TimeUnit.SECONDS.toNanos(30));
        }
    }

    /**
     * @param request exact request class @param side target side @return checked metadata
     */
    private static Entry require(AsyncRequest<?> request, Side side) {
        Objects.requireNonNull(request, "request");
        var entry = byClass.get(request.getClass());
        if (entry == null)
            throw new IllegalStateException("Request is not indexed: " + request.getClass().getName() + "; enable the SDMAL annotation processor");
        if (entry.side != side)
            throw new IllegalStateException("Request " + entry.id + " receives on " + entry.side + ", not " + side);
        return entry;
    }

    /**
     * @param codec binary format @param value non-null payload @return owned encoded payload bytes
     */
    private static byte[] encode(EcsNetworkSerializer<Object> codec, Object value) {
        Objects.requireNonNull(value, "Async result cannot be null; use Void for acknowledgements");
        var buffer = new FriendlyByteBuf(Unpooled.buffer(256, NetworkRuntime.MAX_LOGICAL_BYTES));
        try {
            codec.write(buffer, value);
            var bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /**
     * @param codec binary format @param envelope bounded logical body @return decoded result
     */
    private static Object decode(EcsNetworkSerializer<Object> codec, AsyncEnvelope.Decoded envelope) {
        var buffer = envelope.body();
        try {
            var value = codec.read(buffer);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing async body bytes");
            return value;
        } finally {
            buffer.release();
        }
    }

    /**
     * @param envelope rejected request @param peer exact source connection @param message safe failure text
     */
    private static void sendFailure(AsyncEnvelope.Decoded envelope, NetworkPeer peer, String message) {
        peer.send(AsyncEnvelope.encode(envelope.id(), false, envelope.subject(), false, message, new byte[0]));
    }

    /**
     * @param failure user/schema error; VM/linkage failures must never become successful network processing
     */
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) throw error;
        if (failure instanceof ThreadDeath error) throw error;
        if (failure instanceof LinkageError error) throw error;
    }

    /**
     * One immutable logical request and its lazily built binary field plans.
     */
    private static final class Entry {
        /**
         * Stable namespaced request id.
         */
        final String id;
        /**
         * Side executing the request handler.
         */
        final Side side;
        /**
         * Concrete result type or Void for an acknowledgement.
         */
        final Class<?> responseClass;
        /**
         * Request lifetime in milliseconds, in (0, 86400000].
         */
        final long timeoutMillis;
        /**
         * Concrete indexed request implementation.
         */
        final Class<?> requestClass;
        /**
         * Cached request field codec.
         */
        private volatile EcsNetworkSerializer<Object> requestCodec;
        /**
         * Cached result codec.
         */
        private volatile EcsNetworkSerializer<Object> responseCodec;

        /**
         * @param type indexed request implementation
         */
        Entry(Class<?> type) {
            var annotation = type.getDeclaredAnnotation(NetworkRequest.class);
            if (annotation == null || !AsyncRequest.class.isAssignableFrom(type) || type.isInterface()
                    || Modifier.isAbstract(type.getModifiers()) || !Modifier.isPublic(type.getModifiers())
                    || type.getTypeParameters().length != 0)
                throw new IllegalStateException("Expected public concrete @NetworkRequest: " + type.getName());
            if (!annotation.id().matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || annotation.id().length() > 256
                    || annotation.timeoutMillis() <= 0 || annotation.timeoutMillis() > TimeUnit.DAYS.toMillis(1))
                throw new IllegalStateException("Invalid @NetworkRequest metadata: " + type.getName());
            var generic = NetworkSerialization.interfaceValue(type, AsyncRequest.class);
            if (!annotation.response().equals(generic))
                throw new IllegalStateException("@NetworkRequest.response must equal AsyncRequest<R>: " + type.getName());
            this.id = annotation.id();
            this.side = annotation.side();
            this.responseClass = annotation.response();
            this.timeoutMillis = annotation.timeoutMillis();
            this.requestClass = type;
        }

        /**
         * @return shared automatic request field codec
         */
        @SuppressWarnings("unchecked")
        EcsNetworkSerializer<Object> requestCodec() {
            var result = requestCodec;
            if (result != null) return result;
            synchronized (this) {
                if (requestCodec == null)
                    requestCodec = (EcsNetworkSerializer<Object>) NetworkSerialization.packetCodec(requestClass);
                return requestCodec;
            }
        }

        /**
         * @return shared native scalar/custom/automatic result codec
         */
        @SuppressWarnings("unchecked")
        EcsNetworkSerializer<Object> responseCodec() {
            var result = responseCodec;
            if (result != null) return result;
            synchronized (this) {
                if (responseCodec == null)
                    responseCodec = (EcsNetworkSerializer<Object>) NetworkSerialization.responseCodec(responseClass);
                return responseCodec;
            }
        }
    }

    /**
     * Pending caller request with exact peer binding.
     */
    private static final class Pending {
        /**
         * Request metadata.
         */
        final Entry entry;
        /**
         * Expected response source, including local logical side.
         */
        final NetworkPeer peer;
        /**
         * Caller-visible result future; cancellation removes this entry.
         */
        final CompletableFuture<Object> future;
        /**
         * Scheduled deadline, cancelled on every terminal path.
         */
        volatile ScheduledFuture<?> timeout;

        /**
         * @param entry metadata @param peer expected source @param future caller result
         */
        Pending(Entry entry, NetworkPeer peer, CompletableFuture<Object> future) {
            this.entry = entry;
            this.peer = peer;
            this.future = future;
        }

        /**
         * Cancels even long deadlines without keeping the peer alive in a timer queue.
         */
        void cancelTimeout() {
            var current = timeout;
            if (current != null) current.cancel(false);
        }
    }

    /**
     * Receiving correlation id scoped to the exact connection and local side.
     */
    private record IncomingKey(Side side, Object connection, long id) {
    }

    /**
     * Exactly-once responder; no response payload is retained after scheduling its send.
     */
    private static final class Responder implements AsyncResponder<Object> {
        /**
         * Request metadata.
         */
        final Entry entry;
        /**
         * Original incoming id.
         */
        final IncomingKey key;
        /**
         * Reply connection, checked again after asynchronous completion.
         */
        final NetworkPeer peer;
        /**
         * Game executor captured from the native receiver.
         */
        final Executor executor;
        /**
         * Monotonic request arrival time.
         */
        final long started = System.nanoTime();
        /**
         * Terminal-state guard shared by completion, failure and maintenance.
         */
        final AtomicBoolean done = new AtomicBoolean();

        /**
         * @param entry metadata @param key incoming id @param peer original connection @param executor game executor
         */
        Responder(Entry entry, IncomingKey key, NetworkPeer peer, Executor executor) {
            this.entry = entry;
            this.key = key;
            this.peer = peer;
            this.executor = executor;
        }

        /**
         * @return whether this call won the terminal-state transition
         */
        boolean finish() {
            if (!done.compareAndSet(false, true)) return false;
            synchronized (INCOMING) {
                INCOMING.remove(key, this);
                synchronized (COMPLETED) {
                    COMPLETED.put(key, System.nanoTime());
                    if (COMPLETED.size() > 2048) {
                        var iterator = COMPLETED.keySet().iterator();
                        iterator.next();
                        iterator.remove();
                    }
                }
            }
            return true;
        }

        /**
         * @param response typed non-null result, or null for Void acknowledgements
         */
        @Override
        public void complete(Object response) {
            if (!finish()) return;
            if (expired() || !peer.connected()) return;
            byte[] frame;
            try {
                if (entry.responseClass == Void.class && response != null)
                    throw new IllegalArgumentException("Void request requires a null acknowledgement");
                if (entry.responseClass != Void.class && !entry.responseClass.isInstance(response))
                    throw new IllegalArgumentException("Wrong async response type");
                byte[] body = entry.responseClass == Void.class ? new byte[0] : encode(entry.responseCodec(), response);
                frame = AsyncEnvelope.encode(key.id(), false, entry.id, true, null, body);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                SDMAdvancedLibrary.LOGGER.error("Cannot encode async response: {}", entry.id, failure);
                frame = AsyncEnvelope.encode(key.id(), false, entry.id, false, "Cannot encode remote response", new byte[0]);
            }
            queue(frame);
        }

        /**
         * @param message deliberate bounded remote error description
         */
        @Override
        public void fail(String message) {
            if (!finish() || expired() || !peer.connected()) return;
            queue(AsyncEnvelope.encode(key.id(), false, entry.id, false, message, new byte[0]));
        }

        /**
         * @return whether a result can no longer be delivered
         */
        @Override
        public boolean isDone() {
            return done.get() || !peer.connected() || expired();
        }

        /**
         * @return whether the original monotonic request deadline has passed
         */
        private boolean expired() {
            return System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(entry.timeoutMillis);
        }

        /**
         * @param frame snapshot safe to schedule from a worker thread
         */
        private void queue(byte[] frame) {
            executor.execute(() -> {
                if (!peer.connected()) return;
                try {
                    peer.send(frame);
                } catch (RuntimeException failure) {
                    SDMAdvancedLibrary.LOGGER.warn("Async reply send failed: {}", entry.id, failure);
                }
            });
        }
    }
}

