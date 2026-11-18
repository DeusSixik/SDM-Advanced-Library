package dev.sixik.sdmal.example.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the same executable walkthrough that library users run, including persisted migration.
 */
class RepositoryUsageExampleTest {
    /**
     * Isolated parent for all example data in this test.
     */
    @TempDir Path directory;

    /**
     * The walkthrough leaves identical records readable after all original handles have closed.
     */
    @Test
    void runLocal_crudCacheAndMigration_preserveTwoRecordsAcrossReopen() throws Exception {
        var result = RepositoryUsageExample.runLocal(directory);
        assertEquals(2, result.entries());
        assertEquals("building_updated", result.savedShop().catalogId());
        assertEquals(21, result.savedShop().order());

        try (var jsonManager = Repositories.json(result.directory().resolve("json"));
                var nbtManager = Repositories.nbt(result.directory().resolve("nbt"));
                var sqlManager = Repositories.h2("jdbc:h2:file:" + result.directory().resolve("sql/shops"));
                var json = jsonManager.open("shops", ShopRepositoryExample.DEFINITION);
                var nbt = nbtManager.open("shops", ShopRepositoryExample.DEFINITION);
                var sql = sqlManager.open("shops", ShopRepositoryExample.DEFINITION);
                var versioned = jsonManager.open("versioned_shops", VersionedShopCodec.DEFINITION)) {
            assertEquals(result.savedShop(), json.find(result.savedShop().id()).orElseThrow());
            assertEquals(json.findAll(), nbt.findAll());
            assertEquals(json.findAll(), sql.findAll());
            assertEquals(result.savedShop(), versioned.find(result.savedShop().id()).orElseThrow());
        }
    }

    /**
     * Repeating the demo does not overwrite or delete the first run's values.
     */
    @Test
    void runLocal_repeatedInvocations_useIndependentDirectories() throws Exception {
        var first = RepositoryUsageExample.runLocal(directory);
        var second = RepositoryUsageExample.runLocal(directory);
        assertNotEquals(first.directory(), second.directory());
        assertNotEquals(first.savedShop().id(), second.savedShop().id());
        try (var manager = Repositories.json(first.directory().resolve("json"));
                var repository = manager.open("shops", ShopRepositoryExample.DEFINITION)) {
            assertEquals(first.savedShop(), repository.find(first.savedShop().id()).orElseThrow());
            assertTrue(repository.find(second.savedShop().id()).isEmpty());
        }
    }

    /**
     * A codec migration reads legacy documents but writes only the current schema.
     */
    @Test
    void versionedCodec_legacyProperty_migratesAndWritesCurrentVersion() {
        UUID id = UUID.randomUUID();
        String old = "{\"id\":\"" + id + "\",\"catalog\":\"legacy\",\"order\":3}";
        var codec = new VersionedShopCodec();
        var value = codec.decode(old);
        assertEquals(new ShopRecord(id, "legacy", 3), value);
        assertEquals(value, codec.decode(codec.encode(value)));
        var encoded = com.google.gson.JsonParser.parseString(codec.encode(value)).getAsJsonObject();
        assertEquals(1, encoded.get("version").getAsInt());
        assertTrue(encoded.has("catalog_id"));
        assertFalse(encoded.has("catalog"));
    }

    /**
     * Unsupported future formats and incomplete documents fail visibly.
     */
    @Test
    void versionedCodec_futureOrMalformedDocument_rejectsData() {
        var codec = new VersionedShopCodec();
        assertThrows(RepositoryException.class, () -> codec.decode("{\"version\":2}"));
        assertThrows(RepositoryException.class, () -> codec.decode("{\"version\":1}"));
        assertThrows(RepositoryException.class, () -> codec.decode("{\"version\":1.5}"));
        assertThrows(RepositoryException.class, () -> codec.decode("broken"));
    }
}
