package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.repository.ChangeAwareRepository;
import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryChange;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.api.repository.RepositoryStorage;
import dev.sixik.sdmal.api.repository.RepositorySubscription;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Opt-in two-client MongoDB example. Requires a running replica set or sharded cluster.
 * Opens only the supplied database and creates a unique example collection; no database is dropped.
 * The blocking wait is suitable for this command-line walkthrough, not for a Minecraft tick.
 */
public final class MongoRepositoryExample {
    /**
     * Prevents construction.
     */
    private MongoRepositoryExample() {}

    /**
     * Runs the example only against an explicitly supplied MongoDB endpoint.
     * @param arguments connection URI followed by database name
     * @throws InterruptedException if the walkthrough is interrupted while awaiting notifications
     */
    public static void main(String[] arguments) throws InterruptedException {
        if (arguments.length != 2)
            throw new IllegalArgumentException("Expected MongoDB URI and example database name");
        String collection = run(arguments[0], arguments[1]);
        System.out.println("MongoDB example completed; created collection: " + collection);
    }

    /**
     * Simulates two independent servers sharing one shop collection.
     * @param uri explicit MongoDB connection URI
     * @param database database chosen by the caller
     * @return generated collection name; the demo shop is deleted before successful completion
     * @throws InterruptedException when waiting for change delivery is interrupted
     * @throws RepositoryException when observation fails or does not deliver within ten seconds
     */
    public static String run(String uri, String database) throws InterruptedException {
        String collection = "shops_example_" + UUID.randomUUID().toString().replace("-", "");
        try (var firstServer = Repositories.mongo(uri, database);
                var secondServer = Repositories.mongo(uri, database);
                var remote = secondServer.open(collection, ShopRepositoryExample.DEFINITION);
                var cache = new RepositoryStorage<>(
                        firstServer.open(collection, ShopRepositoryExample.DEFINITION), true)) {
            cache.loadAll();
            ShopRecord created = new ShopRecord(UUID.randomUUID(), "building", 10);
            remote.upsert(created.id(), created);
            await(cache, () -> cache.get(created.id()).filter(created::equals).isPresent());

            ShopRecord updated = new ShopRecord(created.id(), "building_remote_update", 20);
            remote.upsert(updated.id(), updated);
            await(cache, () -> cache.get(updated.id()).filter(updated::equals).isPresent());

            remote.delete(updated.id());
            await(cache, () -> cache.get(updated.id()).isEmpty());
        }
        return collection;
    }

    /**
     * Alternative to the automatic cache: subscribe with application-owned callbacks and lifetime.
     * Callbacks run on a backend worker and must dispatch live ECS work onto its owner executor.
     * @param repository open change-aware repository
     * @param onChange receiver of key invalidations; read the latest record rather than replaying stale data
     * @param onFailure terminal observation failure receiver
     * @return listener handle; close before the repository or its manager
     */
    @SuppressWarnings("unchecked")
    public static RepositorySubscription subscribe(
            Repository<UUID, ShopRecord> repository,
            Consumer<RepositoryChange<UUID>> onChange, Consumer<Throwable> onFailure) {
        if (!(repository instanceof ChangeAwareRepository<?, ?> changeAware))
            throw new IllegalArgumentException("This backend does not support change observation");
        return ((ChangeAwareRepository<UUID, ShopRecord>) changeAware).subscribe(onChange, onFailure);
    }

    /**
     * Waits only in this standalone demo; an application normally reacts to notifications.
     * @param cache observed cache whose terminal error must not be hidden
     * @param received expected local state
     * @throws InterruptedException when the caller interrupts the walkthrough
     * @throws RepositoryException on a stream failure or timeout
     */
    private static void await(RepositoryStorage<UUID, ShopRecord> cache, BooleanSupplier received)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (cache.syncFailure().isPresent())
                throw new RepositoryException("Example change stream failed", cache.syncFailure().orElseThrow());
            if (received.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new RepositoryException("Example MongoDB update did not arrive within ten seconds");
    }
}
