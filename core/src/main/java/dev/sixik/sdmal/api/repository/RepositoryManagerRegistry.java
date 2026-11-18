package dev.sixik.sdmal.api.repository;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Registry for application-provided repository manager factories.
 */
public final class RepositoryManagerRegistry {
    /**
     * Registered factories; only lookup and mutation hold this instance's monitor.
     */
    private final Object2ObjectOpenHashMap<String, Supplier<? extends RepositoryManager>>
            factories = new Object2ObjectOpenHashMap<>();

    /**
     * Registers a manager factory under a stable identifier.
     *
     * @param id      backend identifier
     * @param factory manager factory
     * @throws IllegalStateException when the identifier is already registered
     */
    public synchronized void register(String id, Supplier<? extends RepositoryManager> factory) {
        String key = normalize(id);
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(key, factory) != null)
            throw new IllegalStateException("Repository manager already registered: " + key);
    }

    /**
     * Creates a manager by identifier.
     *
     * @param id backend identifier
     * @return new manager instance
     * @throws IllegalArgumentException if no factory is registered
     */
    public RepositoryManager create(String id) {
        String key = normalize(id);
        Supplier<? extends RepositoryManager> factory;
        synchronized (this) {
            factory = factories.get(key);
        }
        if (factory == null)
            throw new IllegalArgumentException("Unknown repository manager: " + key);
        return Objects.requireNonNull(factory.get(), "repository manager factory returned null");
    }

    /**
     * Removes a registered factory.
     *
     * @param id backend identifier
     * @return true when an entry was removed
     */
    public synchronized boolean unregister(String id) {
        return factories.remove(normalize(id)) != null;
    }

    /**
     * @param id backend id
     * @return locale-independent normalized key
     */
    private static String normalize(String id) {
        Objects.requireNonNull(id, "id");
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank())
            throw new IllegalArgumentException("Repository manager id is blank");
        return normalized;
    }
}
