package dev.sixik.sdmal.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.api.repository.RepositoryStorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

class RepositoryBackendTest {
    private static final RepositoryDefinition<String, StoredValue> DEFINITION =
            new RepositoryDefinition<>(
                    value -> value,
                    value -> value,
                    StoredValue::id,
                    RepositoryCodec.gson(StoredValue.class));

    @TempDir Path directory;

    @Test
    void jsonRepository_roundTripsAndDoesNotUseKeysAsPaths() {
        var manager = Repositories.json(directory.resolve("json"));
        Repository<String, StoredValue> repository = manager.open("shops", DEFINITION);
        String key = "mod:../../outside\\entry";
        repository.upsert(key, new StoredValue(key, 12));

        assertEquals(Optional.of(new StoredValue(key, 12)), repository.find(key));
        assertEquals(Map.of(key, new StoredValue(key, 12)), repository.findAll());
        assertTrue(repository.delete(key));
        assertFalse(repository.delete(key));
        assertTrue(repository.findAll().isEmpty());
        repository.close();
        manager.close();
    }

    @Test
    void jsonRepository_reopensPersistedValuesAndRejectsMismatchedKeys() {
        Path root = directory.resolve("reopen");
        var manager = Repositories.json(root);
        var repository = manager.open("catalog", DEFINITION);
        repository.upsert("shop:one", new StoredValue("shop:one", 42));
        repository.close();
        manager.close();

        var reopenedManager = Repositories.json(root);
        var reopened = reopenedManager.open("catalog", DEFINITION);
        assertEquals(42, reopened.find("shop:one").orElseThrow().amount());
        assertThrows(
                IllegalArgumentException.class,
                () -> reopened.upsert("shop:one", new StoredValue("shop:two", 9)));
        reopenedManager.close();
    }

    @Test
    void jdbcRepository_supportsUpsertPartitionsAndReadThroughStorage() {
        var manager =
                Repositories.h2(
                        "jdbc:h2:mem:repository_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var repository = manager.open("shops", DEFINITION);
        var otherRepository = manager.open("orders", DEFINITION);
        var storage = new RepositoryStorage<>(repository);

        storage.save("shop:one", new StoredValue("shop:one", 7));
        assertEquals(7, storage.load("shop:one").orElseThrow().amount());
        assertTrue(otherRepository.findAll().isEmpty());
        repository.upsert("shop:one", new StoredValue("shop:one", 19));
        assertEquals(19, repository.find("shop:one").orElseThrow().amount());
        assertTrue(repository.delete("shop:one"));
        assertFalse(repository.delete("shop:one"));
        storage.close();
        otherRepository.close();
        manager.close();
    }

    @Test
    void managerRejectsUnsafeNamesAndClosedHandles() {
        var manager = Repositories.json(directory);
        assertThrows(IllegalArgumentException.class, () -> manager.open("../escape", DEFINITION));
        var repository = manager.open("safe", DEFINITION);
        manager.close();
        assertThrows(IllegalStateException.class, () -> repository.find("key"));
    }

    private record StoredValue(String id, int amount) {}
}
