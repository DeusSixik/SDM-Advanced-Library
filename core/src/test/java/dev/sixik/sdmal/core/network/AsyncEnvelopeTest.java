package dev.sixik.sdmal.core.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Checks strict framing independently of request metadata and user codecs. */
class AsyncEnvelopeTest {
    /** Header parsing retains an exact bounded body view. */
    @Test void decode_validRequest_returnsOriginalCorrelationAndBody() {
        var decoded = AsyncEnvelope.decode(AsyncEnvelope.encode(71, true, "test:request", true, null, new byte[] {1, 2}));
        assertTrue(decoded.request()); assertEquals(71, decoded.id()); assertEquals("test:request", decoded.subject());
        var body = decoded.body();
        try { assertEquals(1, body.readByte()); assertEquals(2, body.readByte()); assertFalse(body.isReadable()); }
        finally { body.release(); }
    }
    /** Malformed lengths, version flags and trailing bytes never reach the payload decoder. */
    @Test void decode_malformedFlagsOrTrailingBytes_rejects() {
        var frame = AsyncEnvelope.encode(1, true, "test:request", true, null, new byte[] {1, 2});
        assertThrows(IllegalArgumentException.class, () -> AsyncEnvelope.decode(Arrays.copyOf(frame, frame.length + 1)));
        var badVersion = frame.clone(); badVersion[1] = 99;
        assertThrows(IllegalArgumentException.class, () -> AsyncEnvelope.decode(badVersion));
        var badFlag = frame.clone(); badFlag[10] = 2;
        assertThrows(IllegalArgumentException.class, () -> AsyncEnvelope.decode(badFlag));
    }
    /** Remote error strings are bounded without transmitting a stack trace. */
    @Test void encode_longError_truncatesToProtocolLimit() {
        var decoded = AsyncEnvelope.decode(AsyncEnvelope.encode(1, false, "test:request", false, "x".repeat(2000), new byte[0]));
        assertFalse(decoded.success()); assertEquals(AsyncEnvelope.MAX_ERROR, decoded.error().length()); assertEquals(0, decoded.length());
    }
}
