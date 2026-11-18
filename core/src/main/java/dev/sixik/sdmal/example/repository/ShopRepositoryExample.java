package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.repository.RepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.example.ecs.shop.CatalogComponent;

import java.util.UUID;

/**
 * Captures an ECS shop as detached data usable by every repository backend.
 * @see RepositoryUsageExample
 * @see AsyncEcsRepositoryExample
 * @see MongoRepositoryExample
 */
public final class ShopRepositoryExample {
    /**
     * Backend-independent codec definition; no manual repository subclass is needed.
     */
    public static final RepositoryDefinition<UUID, ShopRecord> DEFINITION =
            new RepositoryDefinition<>(
                    UUID::toString,
                    UUID::fromString,
                    ShopRecord::id,
                    RepositoryCodec.gson(ShopRecord.class));

    /**
     * Prevents construction.
     */
    private ShopRepositoryExample() {}

    /**
     * Reads live ECS data on its owner thread before handing the immutable record to IO workers.
     * @param shop live shop containing one CatalogComponent
     * @return detached shop metadata
     */
    public static ShopRecord snapshot(EcsObject shop) {
        CatalogComponent catalog = shop.getSingle(CatalogComponent.class).orElseThrow();
        return new ShopRecord(shop.id(), catalog.catalogId(), catalog.order());
    }

    /**
     * Detached storage data independent of Minecraft worlds and component ownership.
     * @param id stable ECS object identity
     * @param catalogId application catalog identifier
     * @param order catalog display order
     */
    public record ShopRecord(UUID id, String catalogId, int order) {}
}
