package dev.sixik.sdmal.example.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.example.ecs.shop.CatalogComponent;
import dev.sixik.sdmal.example.ecs.shop.ShopEcsExample;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uses a real IO thread and an explicit owner-thread queue to prove capture/apply boundaries.
 */
class AsyncEcsRepositoryExampleTest {
    /**
     * Isolated file backend.
     */
    @TempDir Path directory;

    /**
     * Prepares Minecraft serialization dependencies and the ordinary example component type.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ComponentRegistry.instance().registerAnnotated(CatalogComponent.class);
    }

    /**
     * IO writes the captured DTO even if live ECS changes before that IO task starts.
     */
    @Test
    void saveShop_mutationBeforeIo_savesOriginalSnapshot() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var manager = Repositories.json(directory);
                var repository = manager.open("shops", ShopRepositoryExample.DEFINITION);
                var ecs = new EcsObjectManager()) {
            var shop = ShopEcsExample.createShop(ecs, "captured", 5);
            var pendingIo = new ArrayBlockingQueue<Runnable>(1);
            var save = AsyncEcsRepositoryExample.saveShop(shop, repository, pendingIo::add);
            shop.getSingle(CatalogComponent.class).orElseThrow().rename("changed_later");

            worker.submit(pendingIo.remove()).get(5, TimeUnit.SECONDS);
            save.get(5, TimeUnit.SECONDS);
            assertEquals("captured", repository.find(shop.id()).orElseThrow().catalogId());
            assertEquals("changed_later", shop.getSingle(CatalogComponent.class).orElseThrow().catalogId());
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * Archive read runs on IO; component reconstruction is explicitly dispatched to the owner.
     */
    @Test
    void archive_roundTrip_appliesOnlyAfterOwnerQueueRuns() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var manager = Repositories.nbt(directory);
                var repository = manager.open("archive", AsyncEcsRepositoryExample.ARCHIVES);
                var source = new EcsObjectManager();
                var target = new EcsObjectManager()) {
            var shop = ShopEcsExample.createShop(source, "archived", 8);
            AsyncEcsRepositoryExample.saveArchive(source, repository, worker).get(5, TimeUnit.SECONDS);

            var ownerQueue = new ArrayBlockingQueue<Runnable>(1);
            var restored = AsyncEcsRepositoryExample.restoreArchive(target, repository, worker, ownerQueue::add);
            Runnable apply = ownerQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull(apply);
            assertFalse(restored.isDone());
            assertTrue(target.get(shop.id()).isEmpty());
            apply.run();

            assertTrue(restored.get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals("archived", target.get(shop.id()).orElseThrow()
                    .getSingle(CatalogComponent.class).orElseThrow().catalogId());
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * A missing archive is a successful empty result; backend errors are exceptional futures.
     */
    @Test
    void restore_missingArchiveAndClosedBackend_keepFailuresVisible() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var manager = Repositories.json(directory);
                var repository = manager.open("archive", AsyncEcsRepositoryExample.ARCHIVES);
                var target = new EcsObjectManager()) {
            var ownerQueue = new ArrayBlockingQueue<Runnable>(1);
            var restored = AsyncEcsRepositoryExample.restoreArchive(target, repository, worker, ownerQueue::add);
            Runnable apply = ownerQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull(apply);
            apply.run();
            assertEquals(List.of(), restored.get(5, TimeUnit.SECONDS));

            repository.close();
            var failed = AsyncEcsRepositoryExample.restoreArchive(target, repository, worker, ownerQueue::add);
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause());
            assertTrue(ownerQueue.isEmpty());
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * Live ECS capture cannot be moved into a background task accidentally.
     */
    @Test
    void saveShop_wrongOwnerThread_rejectsBeforeSubmittingIo() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var manager = Repositories.json(directory);
                var repository = manager.open("shops", ShopRepositoryExample.DEFINITION);
                var ecs = new EcsObjectManager()) {
            var shop = ShopEcsExample.createShop(ecs, "owner_only", 0);
            var future = worker.submit(() -> AsyncEcsRepositoryExample.saveShop(shop, repository, worker));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause());
            assertTrue(repository.find(shop.id()).isEmpty());
        } finally {
            worker.shutdownNow();
        }
    }
}
