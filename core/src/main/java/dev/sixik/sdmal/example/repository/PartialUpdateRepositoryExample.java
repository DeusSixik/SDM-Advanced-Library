package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.repository.PatchableRepository;
import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.api.repository.RepositoryConflictException;
import dev.sixik.sdmal.api.repository.RepositoryManager;
import dev.sixik.sdmal.api.repository.RepositorySnapshot;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;

import java.util.UUID;

/**
 * Demonstrates opt-in field updates and explicit conflict handling with the same DTO in H2 or MongoDB.
 * All calls block and belong on the application's IO executor, outside the world tick.
 */
public final class PartialUpdateRepositoryExample {
    /**
     * Prevents construction.
     */
    private PartialUpdateRepositoryExample() {}

    /**
     * Runs the scenario locally without Minecraft or an external database server.
     *
     * @param arguments unused command-line arguments
     */
    public static void main(String[] arguments) {
        try (var manager = Repositories.h2("jdbc:h2:mem:partial_example_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")) {
            var result = run(manager);
            System.out.println("Partially updated shop: " + result.value());
            System.out.println("Revision: " + result.revision());
            System.out.println("Stale write rejected; reloaded before applying an independent edit.");
        }
    }

    /**
     * Creates an isolated collection, edits one field, rejects a stale write, and reloads before
     * deliberately applying an independent catalog rename. The supplied manager remains caller-owned.
     *
     * @param manager H2/JDBC or MongoDB manager with field-update support
     * @return final decoded shop and committed revision
     */
    public static RepositorySnapshot<ShopRecord> run(RepositoryManager manager) {
        String name = "shops_partial_example_" + UUID.randomUUID().toString().replace("-", "");
        try (var shops = manager.openPatchable(name, ShopRepositoryExample.DEFINITION)) {
            UUID id = UUID.randomUUID();
            shops.upsert(id, new ShopRecord(id, "building", 10));
            var original = shops.findVersioned(id).orElseThrow();
            var edited = updateOrder(shops, original, 20);

            boolean rejected = false;
            try {
                updateOrder(shops, original, 999);
            } catch (RepositoryConflictException conflict) {
                rejected = true;
            }
            if (!rejected) {
                throw new IllegalStateException("A stale shop edit must not overwrite a committed change");
            }

            /*
             * The application decides that renaming the catalog is still valid after reloading.
             * Retrying the stale order assignment automatically would erase the user's order 20.
             */
            var latest = shops.findVersioned(id).orElseThrow();
            if (!latest.revision().equals(edited.revision()) || latest.value().order() != 20) {
                throw new IllegalStateException("Committed order was lost");
            }
            return shops.patch(id, latest.revision(), patch -> patch.set("catalogId", "building_renamed"));
        }
    }

    /**
     * Conditionally changes one JSON property without sending the other shop fields in the write.
     *
     * @param shops field repository
     * @param expected previously loaded value and revision
     * @param order new catalog display order
     * @return updated shop and fresh token
     * @throws RepositoryConflictException when the expected snapshot is stale
     */
    public static RepositorySnapshot<ShopRecord> updateOrder(
            PatchableRepository<UUID, ShopRecord> shops, RepositorySnapshot<ShopRecord> expected, int order) {
        return shops.patch(expected.value().id(), expected.revision(), patch -> patch.set("order", order));
    }
}
