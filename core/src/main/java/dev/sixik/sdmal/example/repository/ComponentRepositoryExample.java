package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.repository.NbtRepositoryCodec;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.example.ecs.shop.CatalogComponent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Saves a component directly instead of projecting it to a separate DTO.
 * The stable repository key must be available from persisted component data after decoding;
 * owner() is deliberately absent from restored components.
 */
public final class ComponentRepositoryExample {
    /**
     * May be declared before common setup; its registry lookup is deferred until use.
     */
    public static final NbtRepositoryCodec<CatalogComponent> CODEC =
            RepositoryCodec.component(CatalogComponent.class);


    /**
     * Uses the persisted catalog id as its repository key, not the transient ECS owner's UUID.
     */
    public static final RepositoryDefinition<String, CatalogComponent> DEFINITION =
            new RepositoryDefinition<>(key -> key, key -> key, CatalogComponent::catalogId, CODEC);

    /**
     * Prevents construction.
     */
    private ComponentRepositoryExample() {
    }

    /**
     * Captures a live component on its owner thread and hands a detached copy to the IO executor.
     * Do not mutate that copy while the returned future is pending.
     *
     * @param shop       live ECS shop; call on its creating manager thread after component discovery
     * @param repository open repository using DEFINITION
     * @param io         application-owned executor; repository and manager must outlive its work
     * @return backend write completion or write failure
     */
    public static CompletableFuture<Void> saveCatalog(
            EcsObject shop, Repository<String, CatalogComponent> repository, Executor io) {
        CatalogComponent live = shop.getSingle(CatalogComponent.class).orElseThrow();
        CatalogComponent snapshot = CODEC.readNbt(CODEC.writeNbt(live));
        return CompletableFuture.runAsync(
                () -> repository.upsert(snapshot.catalogId(), snapshot), io);
    }
}
