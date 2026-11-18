package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.core.utils.ObjectArrayView;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Splits owner-thread ECS capture/apply from background repository IO.
 * Executors and repository handles belong to the application and must outlive returned futures.
 * Do not join a future on the game thread when its completion is scheduled onto that same thread.
 */
public final class AsyncEcsRepositoryExample {
    /**
     * Stores one complete ECS manager archive under the fixed key main.
     */
    public static final RepositoryDefinition<String, CompoundTag> ARCHIVES = new RepositoryDefinition<>(
            key -> key, key -> key, archive -> "main", RepositoryCodec.nbt());

    /**
     * Prevents construction.
     */
    private AsyncEcsRepositoryExample() {}

    /**
     * Captures immutable shop metadata now and persists it later.
     * @param shop live shop; invoke this method on its manager's owner thread
     * @param repository open shop repository
     * @param io application-owned executor for blocking IO
     * @return future that completes after the backend has saved, or exposes the write failure
     */
    public static CompletableFuture<Void> saveShop(
            EcsObject shop, Repository<UUID, ShopRecord> repository, Executor io) {
        ShopRecord snapshot = ShopRepositoryExample.snapshot(shop);
        return CompletableFuture.runAsync(() -> repository.upsert(snapshot.id(), snapshot), io);
    }

    /**
     * Captures the full archive on the owner thread, including component persistence metadata.
     * @param manager live ECS manager; access occurs only before submitting IO
     * @param repository open archive repository using ARCHIVES
     * @param io application-owned IO executor
     * @return persistence completion; failures remain visible to the caller
     */
    public static CompletableFuture<Void> saveArchive(
            EcsObjectManager manager, Repository<String, CompoundTag> repository, Executor io) {
        CompoundTag snapshot = manager.writeNbt();
        return CompletableFuture.runAsync(() -> repository.upsert("main", snapshot), io);
    }

    /**
     * Loads detached bytes/data on IO and restores ECS state on its owning game thread.
     * @param freshManager fresh, empty ECS manager created on the owner thread
     * @param repository open archive repository using ARCHIVES
     * @param io executor for the backend read
     * @param owner executor that dispatches onto freshManager's creating thread, such as server::execute
     * @return restoration diagnostics, empty when no archive exists; failures complete exceptionally
     */
    public static CompletableFuture<List<String>> restoreArchive(
            EcsObjectManager freshManager, Repository<String, CompoundTag> repository,
            Executor io, Executor owner) {
        return CompletableFuture.supplyAsync(() -> repository.find("main"), io)
                .thenApplyAsync(archive -> {
                    if (archive.isEmpty()) return ObjectArrayView.<String>of();
                    return freshManager.readNbt(archive.orElseThrow());
                }, owner);
    }
}
