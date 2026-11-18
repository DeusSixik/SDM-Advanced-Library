package dev.sixik.sdmal.api.repository;

/**
 * Removes a repository listener and releases its stream resources; close is idempotent.
 */
@FunctionalInterface
public interface RepositorySubscription extends AutoCloseable {
    /**
     * Stops future delivery; an already executing callback may finish.
     */
    @Override
    void close();
}
