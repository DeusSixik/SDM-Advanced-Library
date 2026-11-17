package dev.sixik.sdmal.core.ecs.serialization;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Composes unambiguous structural binary schemas and compact compatibility fingerprints at initialization. */
final class EcsWireSchema {
    /** Prevents utility construction. */
    private EcsWireSchema() { }

    /**
     * Encodes parts with character lengths so user keys/adapter names cannot create delimiter collisions.
     * @param kind stable format kind including version
     * @param parts ordered nested descriptions
     * @return canonical schema description
     */
    static String describe(String kind, String... parts) {
        StringBuilder result = new StringBuilder(kind).append('[');
        for (String part : parts) result.append(part.length()).append(':').append(part);
        return result.append(']').toString();
    }

    /**
     * Truncates the SHA-256 digest to its first eight bytes, interpreted as a signed big-endian long.
     * This is a schema compatibility check, not authentication or a collision-proof security boundary.
     * @param schema canonical schema description
     * @return deterministic 64-bit fingerprint
     */
    static long fingerprint(String schema) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(schema.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 is unavailable", missing); }
    }
}
