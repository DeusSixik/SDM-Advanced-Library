package dev.sixik.sdmal.api.repository;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-through in-memory cache over a repository; backend operations are synchronous.
 */
public final class RepositoryStorage<K, V> implements AutoCloseable {
    /**
     * Owned backend handle, used under lock.
     */
    private final Repository<K, V> repository;

    /**
     * Serializes cache and backend operations.
     */
    private final Object lock = new Object();

    /**
     * Non-null cached values keyed by their application identity.
     */
    private final Object2ObjectOpenHashMap<K, V> values = new Object2ObjectOpenHashMap<>();

    /**
     * Subscription closed before the repository handle.
     */
    private final RepositorySubscription remoteSubscription;

    /**
     * Guarded by lock; rejects use after shutdown.
     */
    private boolean closed;

    /**
     * Terminal observation failure, or null while no failure has been reported.
     */
    private volatile Throwable syncFailure;

    /**
     * Creates a cache that owns its repository handle.
     *
     * @param repository backend handle
     */
    public RepositoryStorage(Repository<K, V> repository) {
        this(repository, false);
    }

    /**
     * Creates a cache with optional backend observation. Stream opening is synchronous and may
     * fail; MongoDB observation requires a replica set. Values must be detached, thread-safe data.
     *
     * @param repository     owned backend handle
     * @param observeChanges true to subscribe automatically to remote writes
     */
    public RepositoryStorage(Repository<K, V> repository, boolean observeChanges) {
        this.repository = Objects.requireNonNull(repository, "repository");
        remoteSubscription = observeChanges ? subscribeToRemoteChanges(repository) : () -> {
        };
    }

    /**
     * Returns a cached value without touching the backend.
     *
     * @param key value key
     * @return cached value when present
     */
    public Optional<V> get(K key) {
        synchronized (lock) {
            requireOpen();
            return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key")));
        }
    }

    /**
     * Loads and caches a value on a cache miss.
     *
     * @param key value key
     * @return loaded value when present
     */
    public Optional<V> load(K key) {
        synchronized (lock) {
            requireOpen();
            Objects.requireNonNull(key, "key");
            if (values.containsKey(key)) return Optional.of(values.get(key));
            Optional<V> loaded = repository.find(key);
            loaded.ifPresent(value -> values.put(key, value));
            return loaded;
        }
    }

    /**
     * Reloads all data and atomically replaces the cache after a successful backend read.
     *
     * @return independent loaded values
     */
    public Map<K, V> loadAll() {
        synchronized (lock) {
            requireOpen();
            Map<K, V> loaded = repository.findAll();
            values.clear();
            values.putAll(loaded);
            return new Object2ObjectOpenHashMap<>(values);
        }
    }

    /**
     * Saves to the backend and then publishes the value in the cache.
     *
     * @param key   stable value key
     * @param value value to store
     */
    public void save(K key, V value) {
        synchronized (lock) {
            requireOpen();
            repository.upsert(key, value);
            values.put(key, value);
        }
    }

    /**
     * Updates only the local cache.
     *
     * @param key   stable value key
     * @param value local value
     */
    public void putLocal(K key, V value) {
        synchronized (lock) {
            requireOpen();
            values.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        }
    }

    /**
     * Deletes from the backend and then removes the cached value.
     *
     * @param key stable value key
     * @return true when the backend contained a value
     */
    public boolean delete(K key) {
        synchronized (lock) {
            requireOpen();
            boolean removed = repository.delete(key);
            values.remove(key);
            return removed;
        }
    }

    /**
     * Removes one value from the cache without changing persistent storage.
     *
     * @param key stable value key
     * @return true when the cache contained a value
     */
    public boolean unload(K key) {
        synchronized (lock) {
            requireOpen();
            return values.remove(Objects.requireNonNull(key, "key")) != null;
        }
    }

    /**
     * Returns a detached value snapshot.
     *
     * @return independent mutable list of cached values
     */
    public Collection<V> values() {
        synchronized (lock) {
            requireOpen();
            return new ObjectArrayList<>(values.values());
        }
    }

    /**
     * Returns an independent cache snapshot.
     *
     * @return copied key-to-value map
     */
    public Map<K, V> snapshot() {
        synchronized (lock) {
            requireOpen();
            return new Object2ObjectOpenHashMap<>(values);
        }
    }

    /**
     * @return current cached item count
     */
    public int size() {
        synchronized (lock) {
            requireOpen();
            return values.size();
        }
    }

    /**
     * Closes the owned repository and subscription without holding the cache monitor.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            values.clear();
        }
        try {
            remoteSubscription.close();
        } finally {
            repository.close();
        }
    }

    /**
     * @param source backend handle
     * @return subscription or a no-op handle
     */
    @SuppressWarnings("unchecked")
    private RepositorySubscription subscribeToRemoteChanges(Repository<K, V> source) {
        if (!(source instanceof ChangeAwareRepository<?, ?> changeAware))
            throw new IllegalArgumentException("Backend does not support change subscriptions");
        return ((ChangeAwareRepository<K, V>) changeAware)
                .subscribe(this::reloadRemote, failure -> syncFailure = failure);
    }

    /**
     * @return terminal stream failure; reload and recreate the subscription before trusting
     * freshness
     */
    public Optional<Throwable> syncFailure() {
        return Optional.ofNullable(syncFailure);
    }

    /**
     * @param change invalidates a key and reads its latest state, including delete/reinsert races
     */
    private void reloadRemote(RepositoryChange<K> change) {
        synchronized (lock) {
            if (closed) return;
            repository
                    .find(change.key())
                    .ifPresentOrElse(
                            value -> values.put(change.key(), value),
                            () -> values.remove(change.key()));
        }
    }

    /**
     * @throws IllegalStateException if this cache has closed
     */
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Repository storage is closed");
    }
}
