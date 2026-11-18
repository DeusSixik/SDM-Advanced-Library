package dev.sixik.sdmal.api.repository;

import java.util.Objects;
import java.util.function.Function;

/**
 * Stable key and value codecs shared by every repository backend.
 */
public record RepositoryDefinition<K, V>(
        Function<K, String> keyEncoder,
        Function<String, K> keyDecoder,
        Function<V, K> keyExtractor,
        RepositoryCodec<V> codec) {
    /**
     * Validates the immutable definition.
     *
     * @param keyEncoder   stable key-to-string conversion
     * @param keyDecoder   stable string-to-key conversion, also used for remote change events
     * @param keyExtractor value-to-key conversion
     * @param codec        value JSON codec
     */
    public RepositoryDefinition {
        Objects.requireNonNull(keyEncoder, "keyEncoder");
        Objects.requireNonNull(keyDecoder, "keyDecoder");
        Objects.requireNonNull(keyExtractor, "keyExtractor");
        Objects.requireNonNull(codec, "codec");
    }

    /**
     * Converts and validates a repository key.
     *
     * @param key non-null application key
     * @return stable key text, at most 512 characters
     */
    public String encodeKey(K key) {
        Objects.requireNonNull(key, "key");
        String encoded = Objects.requireNonNull(keyEncoder.apply(key), "encoded key");
        if (encoded.isBlank() || encoded.length() > 512)
            throw new IllegalArgumentException("Repository key must contain 1 to 512 characters");
        return encoded;
    }

    /**
     * Decodes a stored key and verifies that the conversion is stable.
     *
     * @param encoded stable key text
     * @return decoded application key
     */
    public K decodeKey(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        K key = Objects.requireNonNull(keyDecoder.apply(encoded), "decoded key");
        if (!encoded.equals(encodeKey(key)))
            throw new IllegalArgumentException(
                    "Repository key codec is not reversible: " + encoded);
        return key;
    }

    /**
     * Extracts the key from an encoded entity and verifies it matches the supplied key.
     *
     * @param suppliedKey key used for the operation
     * @param value       non-null entity
     * @return stable encoded key
     */
    public String verifyEntityKey(K suppliedKey, V value) {
        Objects.requireNonNull(value, "value");
        String expected = encodeKey(suppliedKey);
        K actual = Objects.requireNonNull(keyExtractor.apply(value), "extracted key");
        if (!expected.equals(encodeKey(actual)))
            throw new IllegalArgumentException("Repository key does not match the entity key");
        return expected;
    }
}
