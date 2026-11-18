package dev.sixik.sdmal.core.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.sixik.sdmal.api.network.*;
import dev.sixik.sdmal.api.network.annotations.NetworkRequest;
import dev.sixik.sdmal.example.network.AsyncClientPingRequest;
import dev.sixik.sdmal.example.network.AsyncPingRequest;
import dev.sixik.sdmal.example.network.AsyncPingResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Public request API through native framing, game executors and authenticated connection binding. */
public class AsyncRequestRuntimeTest {
    /** Captured serverbound native frames. */
    private static final List<byte[]> SERVER = new ArrayList<>();
    /** Captured clientbound native frames. */
    private static final List<byte[]> CLIENT = new ArrayList<>();
    /** Current client connection's liveness. */
    private static final AtomicBoolean CONNECTED = new AtomicBoolean(true);
    /** Native connection identity, replaced between tests. */
    private static Object clientConnection;
    /** Real discovered packet formats. */
    private static PacketRegistry registry;
    /** Authenticated server player with its own listener. */
    private ServerPlayer serverPlayer;
    /** Local client player. */
    private Player clientPlayer;

    /** Initializes native dependencies and the shared recording transport. */
    @BeforeAll static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NetworkTestTransport.bind(new NetworkRuntime.Transport() {
            /** @param frame native serverbound frame */
            @Override public void toServer(byte[] frame) { SERVER.add(frame); }
            /** @param player target @param frame native clientbound frame */
            @Override public void toPlayer(ServerPlayer player, byte[] frame) { CLIENT.add(frame); }
            /** @return exact fake client connection binding */
            @Override public NetworkRuntime.ClientConnection serverConnection() {
                Object captured = clientConnection;
                return new NetworkRuntime.ClientConnection(captured,
                        () -> CONNECTED.get() && clientConnection == captured);
            }
        });
        registry = PacketRegistry.discover(AsyncRequestRuntimeTest.class.getClassLoader());
    }

    /** @throws ReflectiveOperationException if the native listener layout changes */
    @BeforeEach void reset() throws ReflectiveOperationException {
        SERVER.clear(); CLIENT.clear(); CONNECTED.set(true);
        clientConnection = new Object();
        serverPlayer = player(); clientPlayer = mock(Player.class);
        DeferredRequest.responder = null; DeferredRequest.handled = 0;
        AsyncRequestRuntime.expireConnections();
    }

    /** C2S completes only after dispatching the correlated typed response. */
    @Test void request_serverHandler_completesTypedFuture() throws Exception {
        var future = new AsyncPingRequest(42).send();
        assertFalse(future.isDone());
        toServer(take(SERVER));
        assertFalse(future.isDone());
        toClient(take(CLIENT));
        assertEquals(new AsyncPingResponse(42), future.get(1, TimeUnit.SECONDS));
    }

    /** S2C completes on the original server-side listener. */
    @Test void request_clientHandler_completesTypedFuture() throws Exception {
        var future = new AsyncClientPingRequest(73).send(serverPlayer);
        toClient(take(CLIENT));
        assertFalse(future.isDone());
        toServer(take(SERVER));
        assertEquals(new AsyncPingResponse(73), future.get(1, TimeUnit.SECONDS));
    }

    /** Responses can arrive in reverse order without completing another caller. */
    @Test void requests_reverseResponses_completeMatchingFutures() {
        var first = new AsyncPingRequest(1).send();
        var second = new AsyncPingRequest(2).send();
        var requestFirst = take(SERVER);
        toServer(take(SERVER));
        var responseSecond = take(CLIENT);
        toServer(requestFirst);
        var responseFirst = take(CLIENT);
        toClient(responseSecond);
        assertFalse(first.isDone());
        assertEquals(new AsyncPingResponse(2), second.join());
        toClient(responseFirst);
        assertEquals(new AsyncPingResponse(1), first.join());
    }

    /** Another authenticated player cannot consume the original S2C pending request. */
    @Test void response_wrongPlayer_rejectsWithoutConsumingRequest() throws ReflectiveOperationException {
        var future = new AsyncClientPingRequest(9).send(serverPlayer);
        toClient(take(CLIENT));
        var response = take(SERVER);
        var impostor = player();
        var rejected = new ArrayList<String>();
        NetworkRuntime.dispatch(registry, response, Side.SERVER, () -> impostor, Runnable::run, rejected::add);
        assertEquals(1, rejected.size()); assertFalse(future.isDone());
        toServer(response);
        assertEquals(new AsyncPingResponse(9), future.join());
    }

    /** A response on another client connection cannot consume the original pending id. */
    @Test void response_wrongConnection_rejectsWithoutConsumingRequest() {
        var future = new AsyncPingRequest(9).send();
        toServer(take(SERVER));
        var response = take(CLIENT);
        var rejected = new ArrayList<String>();
        NetworkRuntime.dispatch(registry, response, Side.CLIENT, () -> clientPlayer,
                Runnable::run, rejected::add, new Object());
        assertEquals(1, rejected.size()); assertFalse(future.isDone());
        toClient(response);
        assertEquals(new AsyncPingResponse(9), future.join());
    }

    /** A matching correlation id is insufficient if the request subject differs. */
    @Test void response_wrongSubject_rejectsWithoutConsumingRequest() {
        var future = new AsyncPingRequest(9).send();
        toServer(take(SERVER));
        var response = take(CLIENT);
        var decoded = AsyncEnvelope.decode(response);
        var forged = AsyncEnvelope.encode(decoded.id(), false, "sdmal:other_request", true, null, new byte[0]);
        var rejected = new ArrayList<String>();
        NetworkRuntime.dispatch(registry, forged, Side.CLIENT, () -> clientPlayer,
                Runnable::run, rejected::add, clientConnection);
        assertEquals(1, rejected.size()); assertFalse(future.isDone());
        toClient(response);
        assertEquals(new AsyncPingResponse(9), future.join());
    }

    /** Cancellation releases per-peer quota and removes its timer from the scheduler queue. */
    @Test void cancellation_repeatedRequests_releasesSlotsAndDeadlines() {
        int scheduled = NetworkMaintenance.TIMER.getQueue().size();
        for (int index = 0; index < 160; index++) {
            var future = new AsyncPingRequest(index).send();
            assertFalse(future.isDone()); assertTrue(future.cancel(false));
        }
        assertTrue(NetworkMaintenance.TIMER.getQueue().size() <= scheduled);
        SERVER.clear();
        var future = new AsyncPingRequest(10).send();
        toServer(take(SERVER)); toClient(take(CLIENT));
        assertEquals(new AsyncPingResponse(10), future.join());
    }

    /** Unbounded outgoing requests are rejected before an untracked frame is sent. */
    @Test void request_peerQuota_rejectsUntilSlotIsReleased() {
        var pending = new ArrayList<CompletableFuture<AsyncPingResponse>>();
        try {
            for (int index = 0; index < 128; index++) pending.add(new AsyncPingRequest(index).send());
            assertEquals(128, SERVER.size());
            assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));
            assertInstanceOf(IllegalStateException.class, failure(new AsyncPingRequest(129).send()));
            assertEquals(128, SERVER.size());
            pending.remove(0).cancel(false);
            var next = new AsyncPingRequest(130).send(); pending.add(next);
            assertFalse(next.isDone()); assertEquals(129, SERVER.size());
        } finally { for (var future : pending) future.cancel(false); }
    }

    /** Missing responses expire through the actual production deadline scheduler. */
    @Test void request_noResponse_completesExceptionallyAtDeadline() throws Exception {
        var future = new TimeoutRequest().send();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure(future));
    }

    /** Disconnect maintenance fails waiting callers without retaining the connection until timeout. */
    @Test void request_disconnectedConnection_releasesPendingFuture() {
        var future = new AsyncPingRequest(1).send();
        CONNECTED.set(false); AsyncRequestRuntime.expireConnections();
        assertInstanceOf(IllegalStateException.class, failure(future));
    }

    /** A replacement client connection invalidates the previous connection's request. */
    @Test void request_replacedConnection_failsOriginalFuture() {
        var future = new AsyncPingRequest(1).send();
        clientConnection = new Object(); AsyncRequestRuntime.expireConnections();
        assertInstanceOf(IllegalStateException.class, failure(future));
    }

    /** Explicit remote errors and Void acknowledgements complete the matching future. */
    @Test void request_failureAndAcknowledgement_completeCorrespondingFutures() {
        var failed = new FailureRequest("No permission").send();
        toServer(take(SERVER)); toClient(take(CLIENT));
        assertEquals("No permission", assertInstanceOf(RemoteRequestException.class, failure(failed)).getMessage());
        var acknowledged = new AcknowledgementRequest().send();
        toServer(take(SERVER)); toClient(take(CLIENT));
        assertNull(acknowledged.join());
    }

    /** Replayed incoming ids and repeated responder completion never repeat user work or a response. */
    @Test void request_duplicateId_handlesAndRepliesOnce() {
        var future = new DeferredRequest().send(); var request = take(SERVER);
        toServer(request); toServer(request);
        assertEquals(1, DeferredRequest.handled); assertFalse(DeferredRequest.responder.isDone());
        DeferredRequest.responder.complete(12L);
        DeferredRequest.responder.complete(13L); DeferredRequest.responder.fail("late failure");
        toServer(request);
        assertTrue(DeferredRequest.responder.isDone());
        assertEquals(1, DeferredRequest.handled); assertEquals(1, CLIENT.size());
        toClient(take(CLIENT)); assertEquals(12L, future.join());
    }

    /** Background result completion queues its native send on the captured game executor. */
    @Test void responder_backgroundStage_queuesReplyOnGameExecutor() throws Exception {
        var future = new DeferredRequest().send(); var work = new ArrayList<Runnable>();
        NetworkRuntime.dispatch(registry, take(SERVER), Side.SERVER, () -> serverPlayer, work::add, message -> fail(message));
        assertNull(DeferredRequest.responder); work.remove(0).run();
        var result = new CompletableFuture<Long>(); DeferredRequest.responder.completeWith(result);
        CompletableFuture.runAsync(() -> result.complete(91L)).get(2, TimeUnit.SECONDS);
        assertTrue(CLIENT.isEmpty()); assertEquals(1, work.size());
        work.remove(0).run(); toClient(take(CLIENT)); assertEquals(91L, future.join());
    }

    /** Background failure is returned as a bounded remote error, without leaking its exception text. */
    @Test void responder_failedBackgroundStage_completesRemoteException() {
        var future = new DeferredRequest().send(); toServer(take(SERVER));
        DeferredRequest.responder.completeWith(CompletableFuture.failedFuture(new IllegalStateException("private details")));
        toClient(take(CLIENT));
        assertEquals("Asynchronous request failed", assertInstanceOf(RemoteRequestException.class, failure(future)).getMessage());
    }

    /** Expired receiving handlers cannot enqueue late replies, even before maintenance next runs. */
    @Test void responder_deadlinePassed_dropsLateCompletion() throws Exception {
        var future = new ShortDeferredRequest().send(); toServer(take(SERVER));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS)).get(2, TimeUnit.SECONDS);
        assertTrue(ShortDeferredRequest.responder.isDone());
        ShortDeferredRequest.responder.complete(1L);
        assertTrue(CLIENT.isEmpty());
    }

    /** Deferred replies cannot target a replacement server listener. */
    @Test void responder_listenerReplacedBeforeSend_dropsQueuedResponse() throws ReflectiveOperationException {
        var future = new DeferredRequest().send(); var work = new ArrayList<Runnable>();
        NetworkRuntime.dispatch(registry, take(SERVER), Side.SERVER, () -> serverPlayer, work::add, message -> fail(message));
        work.remove(0).run(); DeferredRequest.responder.complete(1L);
        serverPlayer.connection = player().connection; work.remove(0).run();
        assertTrue(CLIENT.isEmpty()); future.cancel(false);
    }

    /** Large request and response use the production transport, including out-of-order fragment delivery. */
    @Test void request_largeBodyAndResponse_roundTripsThroughTransport() {
        var data = new byte[200_000];
        for (int index = 0; index < data.length; index++) data[index] = (byte) (index * 31);
        var future = new LargeRequest(data).send();
        assertTrue(SERVER.size() > 1);
        assertTrue(SERVER.stream().allMatch(frame -> frame.length <= NetworkRuntime.MAX_FRAME_BYTES));
        var requestChunks = List.copyOf(SERVER); SERVER.clear();
        for (int index = requestChunks.size() - 1; index >= 0; index--) toServer(requestChunks.get(index));
        assertTrue(CLIENT.size() > 1);
        assertTrue(CLIENT.stream().allMatch(frame -> frame.length <= NetworkRuntime.MAX_FRAME_BYTES));
        assertFalse(future.isDone()); var responseChunks = List.copyOf(CLIENT); CLIENT.clear();
        for (int index = responseChunks.size() - 1; index >= 0; index--) toClient(responseChunks.get(index));
        assertArrayEquals(data, future.join());
    }

    /** Direction guards run before any frame is handed to the native sender. */
    @Test void request_wrongDirection_rejectsBeforeSending() {
        assertThrows(IllegalStateException.class, () -> new AsyncClientPingRequest(1).send());
        assertThrows(IllegalStateException.class, () -> new AsyncPingRequest(1).send(serverPlayer));
        assertThrows(IllegalArgumentException.class, () -> new AsyncClientPingRequest(1).send(clientPlayer));
        assertTrue(SERVER.isEmpty()); assertTrue(CLIENT.isEmpty());
    }

    /** @param frame serverbound native frame */
    private void toServer(byte[] frame) {
        NetworkRuntime.dispatch(registry, frame, Side.SERVER, () -> serverPlayer, Runnable::run, message -> fail(message));
    }
    /** @param frame clientbound native frame */
    private void toClient(byte[] frame) {
        Object captured = clientConnection;
        NetworkRuntime.dispatch(registry, frame, Side.CLIENT,
                () -> CONNECTED.get() && clientConnection == captured ? clientPlayer : null,
                Runnable::run, message -> fail(message), captured);
    }
    /** @param frames captured transport queue @return its first frame, asserting that it exists */
    private static byte[] take(List<byte[]> frames) { assertFalse(frames.isEmpty()); return frames.remove(0); }
    /** @param future failed result @return its original completion cause */
    private static Throwable failure(CompletableFuture<?> future) { return assertThrows(CompletionException.class, future::join).getCause(); }
    /** @return authenticated player with a live listener on all supported versions
     * @throws ReflectiveOperationException if the native listener layout changes */
    private static ServerPlayer player() throws ReflectiveOperationException {
        var player = mock(ServerPlayer.class);
        var listener = mock(ServerGamePacketListenerImpl.class, invocation ->
                invocation.getMethod().getName().equals("isAcceptingMessages") ? true : RETURNS_DEFAULTS.answer(invocation));
        var connection = mock(Connection.class); when(connection.isConnected()).thenReturn(true);
        for (Class<?> type = ServerGamePacketListenerImpl.class; type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField("connection");
                field.setAccessible(true); field.set(listener, connection);
                player.connection = listener; return player;
            } catch (NoSuchFieldException ignored) {
                /* Modern versions keep the native connection in the common listener superclass. */
            }
        }
        throw new NoSuchFieldException("Native listener connection");
    }

    /** Test-only uncompleted request; excluded from shipped mod indexes. */
    @NetworkRequest(id = "sdmal_test:timeout", side = Side.SERVER, response = Void.class, timeoutMillis = 40)
    public record TimeoutRequest() implements AsyncRequest<Void> {
        /** @param context game context @param responder intentionally uncompleted response */
        @Override public void handle(PacketContext context, AsyncResponder<Void> responder) {}
    }
    /** Controllable test-only response and duplicate invocation counter. */
    @NetworkRequest(id = "sdmal_test:deferred", side = Side.SERVER, response = Long.class)
    public record DeferredRequest() implements AsyncRequest<Long> {
        /** Last request's completion handle. */
        static AsyncResponder<Long> responder;
        /** Actual user handler invocations. */
        static int handled;
        /** @param context game context @param result response captured for later completion */
        @Override public void handle(PacketContext context, AsyncResponder<Long> result) { responder = result; handled++; }
    }
    /** Test-only receiving deadline with a controllable responder. */
    @NetworkRequest(id = "sdmal_test:short_deferred", side = Side.SERVER, response = Long.class, timeoutMillis = 80)
    public record ShortDeferredRequest() implements AsyncRequest<Long> {
        /** Last request's completion handle. */
        static AsyncResponder<Long> responder;
        /** @param context game context @param result response captured for late completion */
        @Override public void handle(PacketContext context, AsyncResponder<Long> result) { responder = result; }
    }
    /** Test-only packet-sized binary request and scalar binary response. */
    @NetworkRequest(id = "sdmal_test:large", side = Side.SERVER, response = byte[].class)
    public record LargeRequest(byte[] data) implements AsyncRequest<byte[]> {
        /** @param context game context @param responder binary response handle */
        @Override public void handle(PacketContext context, AsyncResponder<byte[]> responder) { responder.complete(data); }
    }
    /** Test-only intentional remote error. */
    @NetworkRequest(id = "sdmal_test:failure", side = Side.SERVER, response = Void.class)
    public record FailureRequest(String message) implements AsyncRequest<Void> {
        /** @param context game context @param responder remote error handle */
        @Override public void handle(PacketContext context, AsyncResponder<Void> responder) { responder.fail(message); }
    }
    /** Test-only acknowledgement without a response body. */
    @NetworkRequest(id = "sdmal_test:ack", side = Side.SERVER, response = Void.class)
    public record AcknowledgementRequest() implements AsyncRequest<Void> {
        /** @param context game context @param responder acknowledgement handle */
        @Override public void handle(PacketContext context, AsyncResponder<Void> responder) { responder.complete(null); }
    }
}
