package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.repository.*;
import dev.sixik.sdmal.internal.driver.bson.Document;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClients;
import dev.sixik.sdmal.internal.driver.mongodb.client.model.changestream.ChangeStreamDocument;
import dev.sixik.sdmal.internal.driver.mongodb.client.model.changestream.OperationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MongoDB field-update, concurrency, and remote-cache tests; no mock document engine.
 */
@EnabledIfSystemProperty(named = "sdmal.test.mongo.uri", matches = ".+")
class MongoPatchRepositoryIntegrationTest extends PatchRepositoryContract {
    /**
     * Explicitly supplied disposable MongoDB endpoint.
     */
    private static final String URI = System.getProperty("sdmal.test.mongo.uri");
    /**
     * Exact isolated database retained for cleanup after each test.
     */
    private final String database = "sdmal_patch_test_" + UUID.randomUUID().toString().replace("-", "");

    /**
     * Requires the replica set used by the existing integration tests.
     *
     * @throws Exception if no writable primary becomes available
     */
    @BeforeAll
    static void prepare() throws Exception {
        MongoRepositoryIntegrationTest.prepare();
    }

    /**
     * @return independent client connected to this test's database
     */
    @Override
    RepositoryManager manager() {
        return Repositories.mongo(URI, database);
    }

    /**
     * Drops only the exact unique database created by this test.
     */
    @AfterEach
    void cleanup() {
        try (var client = MongoClients.create(URI)) {
            client.getDatabase(database).drop();
        }
    }

    /**
     * Confirms native UPDATE events contain only selected paths and refresh an existing cache.
     */
    @Test
    void patch_nativeOperators_updateOnlySelectedPathsAndRefreshRemoteCache() throws Exception {
        try (var manager = manager(); var remoteManager = manager(); var inspector = MongoClients.create(URI)) {
            var repository = manager.openPatchable("shops", DEFINITION);
            var remote = remoteManager.openPatchable("shops", DEFINITION);
            String unchanged = "unchanged_large_field_" + "z".repeat(262144);
            repository.upsert("one", new Value("one", 7, unchanged, initial().tags(), initial().details()));
            var collection = inspector.getDatabase(database).getCollection("sdmal_fields_shops");
            try (var cursor = collection.watch().maxAwaitTime(100, TimeUnit.MILLISECONDS).cursor();
                    var cache = new RepositoryStorage<>(repository, true)) {
                cache.load("one");
                var before = remote.findVersioned("one").orElseThrow();
                var result = remote.patch("one", before.revision(), p -> p.set("amount", Long.MAX_VALUE).unset("tags"));
                ChangeStreamDocument<Document> event = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (event == null && System.nanoTime() < deadline) {
                    event = cursor.tryNext();
                }
                assertNotNull(event);
                assertEquals(OperationType.UPDATE, event.getOperationType());
                var description = event.getUpdateDescription();
                assertNotNull(description);
                assertEquals(2, description.getUpdatedFields().size());
                assertEquals(Long.toString(Long.MAX_VALUE), description.getUpdatedFields().getString("fields.amount").getValue());
                assertTrue(description.getUpdatedFields().containsKey("revision"));
                assertEquals(java.util.List.of("fields.tags"), description.getRemovedFields());
                assertFalse(description.toString().contains("unchanged_large_field_"));
                await(() -> cache.get("one").filter(value -> value.amount() == Long.MAX_VALUE).isPresent());
                assertEquals(unchanged, cache.get("one").orElseThrow().note());
                assertNull(cache.get("one").orElseThrow().tags());
                assertTrue(cache.syncFailure().isEmpty());
                assertEquals(result, repository.findVersioned("one").orElseThrow());
            }
        }
    }

    /**
     * @param condition cache state awaited without assuming synchronous change streams
     * @throws InterruptedException if interrupted during the bounded wait
     */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Remote field update did not arrive within ten seconds");
    }
}
