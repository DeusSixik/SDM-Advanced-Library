package dev.sixik.sdmal.core.network;

import io.netty.buffer.Unpooled;

import java.util.Objects;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Internal logical frame for requests and responses, fragmented by NetworkRuntime when needed.
 */
final class AsyncEnvelope {
    /**
     * Magic byte outside the ordinary PacketRegistry version space.
     */
    static final int MAGIC = 0x7B;
    /**
     * Envelope wire version.
     */
    static final int VERSION = 1;
    /**
     * Maximum subject length in UTF-8 characters.
     */
    static final int MAX_SUBJECT = 256;
    /**
     * Maximum remote error text; never sends exception stack traces.
     */
    static final int MAX_ERROR = 1024;
    /**
     * Header bytes before the bounded body: magic, version, id, request flag, subject.
     */
    private static final int MINIMUM = 1 + 1 + Long.BYTES + 1 + 1;

    /**
     * Prevents utility construction.
     */
    private AsyncEnvelope() {
    }

    /**
     * @param frame logical candidate @return whether it is an async envelope
     */
    static boolean is(byte[] frame) {
        return frame.length > 0 && (frame[0] & 0xFF) == MAGIC;
    }

    /**
     * Encodes one request or response envelope.
     *
     * @param id      correlation id
     * @param request whether this is a request rather than a response
     * @param subject indexed request id
     * @param success response success flag; ignored for requests
     * @param error   error text for a failed response
     * @param body    serialized request/response body
     * @return owned logical frame
     */
    static byte[] encode(long id, boolean request, String subject, boolean success, String error, byte[] body) {
        Objects.requireNonNull(subject, "subject");
        if (subject.length() > MAX_SUBJECT || body.length > NetworkRuntime.MAX_LOGICAL_BYTES)
            throw new IllegalArgumentException("Async envelope exceeds its bounds");
        var buffer = new FriendlyByteBuf(Unpooled.buffer(Math.min(256 + body.length, NetworkRuntime.MAX_LOGICAL_BYTES), NetworkRuntime.MAX_LOGICAL_BYTES));
        try {
            buffer.writeByte(MAGIC);
            buffer.writeByte(VERSION);
            buffer.writeLong(id);
            buffer.writeBoolean(request);
            buffer.writeUtf(subject, MAX_SUBJECT);
            if (!request) {
                buffer.writeBoolean(success);
                if (!success) {
                    String message = error == null ? "Remote request failed" : error;
                    buffer.writeUtf(message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR), MAX_ERROR);
                }
            }
            buffer.writeByteArray(body);
            var result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally {
            buffer.release();
        }
    }

    /**
     * @param frame logical envelope @return decoded bounded header/body
     */
    static Decoded decode(byte[] frame) {
        if (!is(frame) || frame.length < MINIMUM || frame.length > NetworkRuntime.MAX_LOGICAL_BYTES)
            throw new IllegalArgumentException("Malformed async envelope");
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        try {
            if (buffer.readUnsignedByte() != MAGIC || buffer.readUnsignedByte() != VERSION)
                throw new IllegalArgumentException("Unsupported async envelope version");
            long id = buffer.readLong();
            boolean request = flag(buffer);
            String subject = buffer.readUtf(MAX_SUBJECT);
            boolean success = true;
            String error = null;
            if (!request) {
                success = flag(buffer);
                if (!success) error = buffer.readUtf(MAX_ERROR);
            }
            int length = buffer.readVarInt();
            if (length < 0 || length != buffer.readableBytes())
                throw new IllegalArgumentException("Invalid async body length or trailing bytes");
            return new Decoded(id, request, subject, success, error, frame, buffer.readerIndex(), length);
        } finally {
            buffer.release();
        }
    }

    /**
     * Parsed immutable envelope.
     */
    record Decoded(long id, boolean request, String subject, boolean success, String error, byte[] frame, int offset,
                   int length) {
        /**
         * @return caller-owned bounded view of this envelope's body; release after use
         */
        FriendlyByteBuf body() {
            return new FriendlyByteBuf(Unpooled.wrappedBuffer(frame, offset, length));
        }
    }

    /**
     * @param buffer source @return strict zero/one flag
     */
    private static boolean flag(FriendlyByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid async boolean flag");
        return value == 1;
    }
}
