package dev.sixik.sdmal.api.repository;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous CRUD contract independent of the selected storage backend.
 */
public interface Repository<K, V> extends AutoCloseable {
    /**
     * Finds one value by key.
     *
     * @param key stable application key
     * @return value when present
     */
    Optional<V> find(K key);

    /**
     * Loads all values into an independent mutable map.
     *
     * @return snapshot keyed by each decoded entity's extracted key
     */
    Map<K, V> findAll();

    /**
     * Inserts or replaces one value.
     *
     * @param key   stable application key
     * @param value non-null entity whose extracted key matches key
     */
    void upsert(K key, V value);

    /**
     * Deletes one value.
     *
     * @param key stable application key
     * @return true when a value existed
     */
    boolean delete(K key);

    /**
     * Inserts or replaces a batch. Backends may override this method with a transaction.
     *
     * @param values key-to-value entries
     */
    default void upsertAll(Map<? extends K, ? extends V> values) {
        Objects.requireNonNull(values, "values");
        for (var entry : values.entrySet()) upsert(entry.getKey(), entry.getValue());
    }

    /**
     * Deletes a batch of keys.
     *
     * @param keys keys to delete
     */
    default void deleteAll(Collection<? extends K> keys) {
        Objects.requireNonNull(keys, "keys");
        for (K key : keys) delete(key);
    }

    /**
     * Closes backend resources owned by this repository.
     */
    @Override
    default void close() {
    }
}
