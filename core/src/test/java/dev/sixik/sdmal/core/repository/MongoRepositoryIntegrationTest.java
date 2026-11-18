package dev.sixik.sdmal.core.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.repository.*;
import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.example.ecs.shop.CatalogComponent;
import dev.sixik.sdmal.example.repository.ComponentRepositoryExample;
import dev.sixik.sdmal.internal.driver.bson.Document;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClient;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClients;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Executes against an explicitly supplied disposable MongoDB replica set; no mock server. */
@EnabledIfSystemProperty(named = "sdmal.test.mongo.uri", matches = ".+")
class MongoRepositoryIntegrationTest {
    /** Test endpoint supplied with -PrepositoryMongoUri. */
    private static final String URI = System.getProperty("sdmal.test.mongo.uri");

    /** Detached test data codec; long values must not lose precision. */
    private static final RepositoryDefinition<String, Value> DEFINITION =
            new RepositoryDefinition<>(
                    s -> s, s -> s, Value::id, RepositoryCodec.gson(Value.class));

    /** Initializes only an explicitly opted-in temporary localhost server. */
    @BeforeAll
    static void prepare() throws Exception {
        try (MongoClient client = MongoClients.create(URI)) {
            if (Boolean.getBoolean("sdmal.test.mongo.init")) {
                Document hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
                if (!hello.containsKey("setName")) {
                    client.getDatabase("admin")
                            .runCommand(new Document("replSetInitiate", new Document()));
                }
            }
            await(
                    () ->
                            Boolean.TRUE.equals(
                                    client.getDatabase("admin")
                                            .runCommand(new Document("hello", 1))
                                            .getBoolean("isWritablePrimary")));
        }
    }

    /** Verifies CRUD, partition isolation, repeated replacement, and independent clients. */
    @Test
    void crud_twoManagers_roundTripsExactValues() {
        String database = databaseName();
        try (var manager = Repositories.mongo(URI, database);
                var second = Repositories.mongo(URI, database)) {
            var repository = manager.open("shops", DEFINITION);
            var other = second.open("shops", DEFINITION);
            var value = new Value("alpha", Long.MAX_VALUE);
            repository.upsert("alpha", value);
            repository.upsert("alpha", value);
            assertEquals(value, other.find("alpha").orElseThrow());
            assertEquals(Map.of("alpha", value), repository.findAll());
            assertTrue(manager.open("orders", DEFINITION).findAll().isEmpty());
            assertTrue(other.delete("alpha"));
            assertFalse(repository.delete("alpha"));
            assertTrue(repository.find("alpha").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repository.upsert("wrong", value));
        } finally {
            drop(database);
        }
    }

    /**
     * Component-valued repositories also restore detached ECS data through remote cache updates.
     */
    @Test
    void components_factoryCodec_roundTripsAndRefreshesRemoteCache() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ComponentRegistry.instance().registerAnnotated(CatalogComponent.class);
        var definition = ComponentRepositoryExample.DEFINITION;
        String database = databaseName();
        try (var first = Repositories.mongo(URI, database);
                var second = Repositories.mongo(URI, database);
                var remote = second.open("components", definition);
                var cache = new RepositoryStorage<>(first.open("components", definition), true)) {
            var component = new CatalogComponent("remote_catalog", 15);
            remote.upsert(component.catalogId(), component);
            await(() -> cache.get("remote_catalog").isPresent());
            var restored = cache.get("remote_catalog").orElseThrow();
            assertNotSame(component, restored);
            assertFalse(restored.isAttached());
            assertEquals(15, restored.order());
            remote.upsert("remote_catalog",
                    new CatalogComponent("remote_catalog", 27));
            await(() -> cache.get("remote_catalog").filter(value -> value.order() == 27).isPresent());
            remote.delete("remote_catalog");
            await(() -> cache.get("remote_catalog").isEmpty());
            assertTrue(cache.syncFailure().isEmpty());
        } finally {
            drop(database);
        }
    }

    /**
     * Confirms remote and same-handle changes refresh a subscribed cache and release its worker.
     */
    @Test
    void streams_remoteUpdatesAndDeletes_refreshCache() throws Exception {
        String database = databaseName();
        try (var first = Repositories.mongo(URI, database);
                var second = Repositories.mongo(URI, database)) {
            var local = first.open("catalog", DEFINITION);
            var remote = second.open("catalog", DEFINITION);
            try (var cache = new RepositoryStorage<>(local, true)) {
                var initial = new Value("one", 11);
                remote.upsert("one", initial);
                await(() -> cache.get("one").filter(initial::equals).isPresent());
                var updated = new Value("one", 12);
                local.upsert("one", updated);
                await(() -> cache.get("one").filter(updated::equals).isPresent());
                remote.delete("one");
                await(() -> cache.get("one").isEmpty());
                assertTrue(cache.syncFailure().isEmpty());
            }
            assertThrows(IllegalStateException.class, () -> local.find("one"));
            first.close();
            remote.upsert("alive", new Value("alive", 3));
            assertTrue(remote.find("alive").isPresent());
        } finally {
            drop(database);
        }
    }

    /** Terminal listener failures reach the error handler instead of silently losing freshness. */
    @Test
    @SuppressWarnings("unchecked")
    void streams_failingListener_reportsFailure() throws Exception {
        String database = databaseName();
        try (var manager = Repositories.mongo(URI, database)) {
            var repository =
                    (ChangeAwareRepository<String, Value>) manager.open("events", DEFINITION);
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            try (var subscription =
                    repository.subscribe(
                            change -> {
                                throw new IllegalStateException("test callback");
                            },
                            failure -> {
                                error.set(failure);
                                failed.countDown();
                            })) {
                repository.upsert("one", new Value("one", 1));
                assertTrue(failed.await(10, TimeUnit.SECONDS));
                assertEquals("test callback", error.get().getMessage());
            }
        } finally {
            drop(database);
        }
    }

    /**
     * @return unique database whose exact name is retained for cleanup
     */
    private static String databaseName() {
        return "sdmal_repository_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @param database exact database created by this test
     */
    private static void drop(String database) {
        try (MongoClient client = MongoClients.create(URI)) {
            client.getDatabase(database).drop();
        }
    }

    /**
     * @param condition state to await @throws Exception if it never arrives
     */
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("MongoDB state did not arrive within ten seconds");
    }

    /**
     * @param id stable key @param amount exact signed 64-bit value
     */
    private record Value(String id, long amount) {}
}
