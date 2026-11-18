package dev.sixik.sdmal.api.repository;

import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Optional repository capability for stores that can observe external writes.
 */
public interface ChangeAwareRepository<K, V> extends Repository<K, V> {
    /**
     * Subscribes to committed changes, including writes through this handle.
     *
     * @param listener receiver; events may arrive from a backend worker thread
     * @return handle that removes this listener when closed
     */
    default RepositorySubscription subscribe(Consumer<RepositoryChange<K>> listener) {
        return subscribe(
                listener,
                failure -> {
                    LoggerFactory.getLogger(ChangeAwareRepository.class)
                            .error(
                                    "Repository change subscription failed; reload the cache and"
                                            + " resubscribe",
                                    failure);
                });
    }

    /**
     * Starts a subscription with an explicit failure receiver. MongoDB requires a replica set or
     * sharded cluster. Opening a stream can block and fails synchronously when unsupported.
     *
     * @param listener  change receiver; must not access live ECS or Minecraft state
     * @param onFailure receiver of terminal stream or listener failures
     * @return subscription handle
     */
    RepositorySubscription subscribe(
            Consumer<RepositoryChange<K>> listener, Consumer<Throwable> onFailure);
}
