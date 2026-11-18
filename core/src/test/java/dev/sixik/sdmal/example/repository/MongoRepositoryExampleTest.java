package dev.sixik.sdmal.example.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClients;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the opt-in walkthrough against a real endpoint selected by the existing integration property.
 */
class MongoRepositoryExampleTest {
    /**
     * Temporary root automatically cleaned by JUnit.
     */
    @TempDir Path directory;

    /**
     * Rejects a missing capability without starting threads or requiring MongoDB.
     */
    @Test
    void subscribe_fileBackend_reportsUnsupportedCapability() {
        try (var manager = Repositories.json(directory);
                var repository = manager.open("shops", ShopRepositoryExample.DEFINITION)) {
            assertThrows(IllegalArgumentException.class,
                    () -> MongoRepositoryExample.subscribe(repository, change -> {}, failure -> {}));
        }
    }

    /**
     * Validates the actual runnable two-client example; cleanup affects only its unique database.
     */
    @Test
    @EnabledIfSystemProperty(named = "sdmal.test.mongo.uri", matches = ".+")
    void run_replicaSet_deliversInsertUpdateAndDelete() throws Exception {
        String uri = System.getProperty("sdmal.test.mongo.uri");
        String database = "sdmal_example_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String collection = MongoRepositoryExample.run(uri, database);
            try (var manager = Repositories.mongo(uri, database);
                    var repository = manager.open(collection, ShopRepositoryExample.DEFINITION)) {
                assertTrue(repository.findAll().isEmpty());
            }
        } finally {
            try (var client = MongoClients.create(uri)) {
                client.getDatabase(database).drop();
            }
        }
    }
}
