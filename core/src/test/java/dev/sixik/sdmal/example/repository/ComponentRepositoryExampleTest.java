package dev.sixik.sdmal.example.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.example.ecs.shop.CatalogComponent;
import dev.sixik.sdmal.example.ecs.shop.ShopEcsExample;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the component-valued example without serializing a live ECS owner on an IO thread.
 */
class ComponentRepositoryExampleTest {
    /**
     * Independent backend path.
     */
    @TempDir Path directory;

    /**
     * Prepares Minecraft dependencies and the component normally discovered by common setup.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ComponentRegistry.instance().registerAnnotated(CatalogComponent.class);
    }

    /**
     * A queued save uses the original component snapshot and returns an attachable component.
     */
    @Test
    void saveCatalog_liveMutationAfterCapture_persistsDetachedOriginal() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        try (var backend = Repositories.json(directory);
                var repository = backend.open("catalogs", ComponentRepositoryExample.DEFINITION);
                var ecs = new EcsObjectManager()) {
            var source = ShopEcsExample.createShop(ecs, "captured_catalog", 9);
            var queue = new ArrayBlockingQueue<Runnable>(1);
            var saved = ComponentRepositoryExample.saveCatalog(source, repository, queue::add);
            source.getSingle(CatalogComponent.class).orElseThrow().rename("later_catalog");
            io.submit(queue.remove()).get(5, TimeUnit.SECONDS);
            saved.get(5, TimeUnit.SECONDS);

            CatalogComponent restored = repository.find("captured_catalog").orElseThrow();
            assertEquals(9, restored.order());
            assertFalse(restored.isAttached());
            assertTrue(repository.find("later_catalog").isEmpty());
            var destination = ecs.create();
            destination.attach(restored);
            assertSame(destination, restored.owner().orElseThrow());
            assertNotSame(source, restored.owner().orElseThrow());
        } finally {
            io.shutdownNow();
        }
    }
}
