package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryManagerRegistry;
import dev.sixik.sdmal.api.repository.RepositoryStorage;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;
import dev.sixik.sdmal.core.utils.ObjectArrayView;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Executable repository walkthrough: CRUD, reopen, cache, and JSON to NBT/H2 migration.
 * Runs blocking IO on the calling thread; invoke from a tool or IO worker, never a world tick.
 * Each run creates its own directory so existing application data is not changed.
 */
public final class RepositoryUsageExample {
    /**
     * Prevents construction.
     */
    private RepositoryUsageExample() {}

    /**
     * Runs the local example with a directory that can be inspected afterwards.
     * @param arguments optional parent directory for the generated example data
     * @throws IOException if the example directory cannot be created
     */
    public static void main(String[] arguments) throws IOException {
        Path root = arguments.length == 0 ? Path.of("build/repository-example") : Path.of(arguments[0]);
        RunResult result = runLocal(root);
        System.out.println("Repository example completed: " + result.directory());
        System.out.println("Shop after cache save and reload: " + result.savedShop());
        System.out.println("Records retained in JSON: " + result.entries());
        System.out.println("Verified copies: NBT and H2");
    }

    /**
     * Executes a complete scenario using the same definition for three different backends.
     * @param parent parent directory; each invocation creates a unique run subdirectory
     * @return paths and persisted results for inspection
     * @throws IOException if creating the example directories fails
     * @throws IllegalStateException if a migration did not reproduce the source records
     */
    public static RunResult runLocal(Path parent) throws IOException {
        Files.createDirectories(parent);
        Path run = Files.createTempDirectory(parent, "run-").toAbsolutePath();
        RepositoryManagerRegistry backends = backends(run);
        UUID shopId;

        try (var manager = backends.create("json");
                var shops = manager.open("shops", ShopRepositoryExample.DEFINITION)) {
            shopId = demonstrateCrud(shops);
        }

        ShopRecord saved;
        try (var manager = backends.create("json");
                var cache = new RepositoryStorage<>(manager.open("shops", ShopRepositoryExample.DEFINITION))) {
            cache.loadAll();
            ShopRecord original = cache.get(shopId).orElseThrow();
            saved = new ShopRecord(original.id(), "building_updated", original.order() + 1);
            cache.save(shopId, saved);

            cache.unload(shopId);
            ShopRecord reloaded = cache.load(shopId).orElseThrow();
            requireEqual(saved, reloaded);

            cache.putLocal(shopId, new ShopRecord(shopId, "unsaved_preview", 999));
            cache.loadAll();
            requireEqual(saved, cache.get(shopId).orElseThrow());

            ShopRecord temporary = new ShopRecord(UUID.randomUUID(), "temporary", 0);
            cache.save(temporary.id(), temporary);
            cache.delete(temporary.id());
        }

        int entries;
        try (var sourceManager = backends.create("json");
                var nbtManager = backends.create("nbt");
                var sqlManager = backends.create("h2");
                var source = sourceManager.open("shops", ShopRepositoryExample.DEFINITION);
                var nbt = nbtManager.open("shops", ShopRepositoryExample.DEFINITION);
                var sql = sqlManager.open("shops", ShopRepositoryExample.DEFINITION)) {
            Map<UUID, ShopRecord> expected = source.findAll();
            entries = Repositories.copy(source, nbt);
            Repositories.copy(nbt, sql);
            requireEqual(expected, nbt.findAll());
            requireEqual(expected, sql.findAll());
            requireEqual(expected, source.findAll());
        }

        try (var manager = backends.create("json");
                var versioned = manager.open("versioned_shops", VersionedShopCodec.DEFINITION)) {
            versioned.upsert(saved.id(), saved);
            requireEqual(saved, versioned.find(saved.id()).orElseThrow());
        }
        return new RunResult(run, saved, entries);
    }

    /**
     * Registers backend factories selected by an application configuration string.
     * @param run unique run directory
     * @return independent factory registry; create owns the returned manager
     */
    private static RepositoryManagerRegistry backends(Path run) {
        var registry = new RepositoryManagerRegistry();
        registry.register("json", () -> Repositories.json(run.resolve("json")));
        registry.register("nbt", () -> Repositories.nbt(run.resolve("nbt")));
        registry.register("h2", () -> Repositories.h2("jdbc:h2:file:" + run.resolve("sql/shops")));
        return registry;
    }

    /**
     * Demonstrates single and batch operations on data belonging only to this run.
     * @param shops repository opened with the shared shop definition
     * @return identity of the primary shop retained for the cache walkthrough
     */
    private static UUID demonstrateCrud(Repository<UUID, ShopRecord> shops) {
        ShopRecord primary = new ShopRecord(UUID.randomUUID(), "building", 10);
        shops.upsert(primary.id(), primary);
        requireEqual(primary, shops.find(primary.id()).orElseThrow());

        ShopRecord renamed = new ShopRecord(primary.id(), "building_renamed", 20);
        shops.upsert(primary.id(), renamed);

        ShopRecord retained = new ShopRecord(UUID.randomUUID(), "food", 30);
        ShopRecord deleted = new ShopRecord(UUID.randomUUID(), "delete_one", 40);
        ShopRecord batchDeleted = new ShopRecord(UUID.randomUUID(), "delete_batch", 50);
        var batch = new Object2ObjectOpenHashMap<UUID, ShopRecord>();
        batch.put(retained.id(), retained);
        batch.put(deleted.id(), deleted);
        batch.put(batchDeleted.id(), batchDeleted);
        shops.upsertAll(batch);
        shops.delete(deleted.id());
        shops.deleteAll(ObjectArrayView.of(batchDeleted.id()));
        requireEqual(2, shops.findAll().size());
        requireEqual(true, shops.find(deleted.id()).isEmpty());
        return primary.id();
    }

    /**
     * Makes failed round-trips visible when the example is executed outside JUnit.
     * @param expected expected persisted value
     * @param actual restored value
     * @throws IllegalStateException when a backend or migration returns different data
     */
    private static void requireEqual(Object expected, Object actual) {
        if (expected.equals(actual)) return;
        throw new IllegalStateException("Repository example round-trip mismatch");
    }

    /**
     * Results retained on disk after the walkthrough closes all repository handles.
     * @param directory unique run root
     * @param savedShop final primary shop data
     * @param entries number of records copied into each backend
     */
    public record RunResult(Path directory, ShopRecord savedShop, int entries) {}
}
