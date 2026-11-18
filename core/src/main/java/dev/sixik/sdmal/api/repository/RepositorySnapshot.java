package dev.sixik.sdmal.api.repository;

import java.util.Objects;
import java.util.UUID;

/**
 * A value and its opaque optimistic concurrency token. The token changes on every write,
 * including full replacement and deletion followed by recreation. Mutable values remain mutable;
 * changing the value in memory does not update the repository.
 *
 * @param value detached decoded value
 * @param revision token to pass to the next conditional patch
 * @param <V> application value type
 */
public record RepositorySnapshot<V>(V value, UUID revision) {
    /**
     * Requires a complete snapshot.
     *
     * @param value non-null decoded value
     * @param revision non-null write token
     */
    public RepositorySnapshot {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(revision, "revision");
    }
}
