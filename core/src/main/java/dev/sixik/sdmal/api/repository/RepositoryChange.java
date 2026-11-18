package dev.sixik.sdmal.api.repository;

import java.util.Objects;

/**
 * A committed external change observed by a change-aware repository.
 */
public record RepositoryChange<K>(Kind kind, K key) {
    /**
     * Change kinds delivered to repository subscribers.
     */
    public enum Kind {
        /**
         * The key was inserted or updated remotely.
         */
        UPSERT,
        /**
         * The key was deleted remotely.
         */
        DELETE
    }

    /**
     * Validates one change event.
     *
     * @param kind event kind
     * @param key  affected key
     */
    public RepositoryChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
    }
}
