package dev.sixik.sdmal.example.ecs.shop;

import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.ecs.ComponentRegistry;

/** Creates shop data in an application-owned manager shared independently of Minecraft dimensions. */
public final class ShopEcsExample {
    /** Prevents utility construction. */
    private ShopEcsExample() {}

    /**
     * Creates a shop with a typed, automatically discovered catalog component.
     * Call after SDMAL setup has discovered the mod's component services. The caller owns the
     * manager's save/load and shutdown. No periodic tick is needed for this data-only composition.
     * @param manager application-owned manager, used on its creating thread
     * @param catalogId non-blank application catalog key
     * @param order relative display order
     * @return registered shop object whose UUID is its stable ECS identity
     * @throws IllegalStateException if the manager is unavailable or component discovery has not run
     */
    public static EcsObject createShop(EcsObjectManager manager, String catalogId, int order) {
        var catalog = new CatalogComponent(catalogId, order);
        ComponentRegistry.instance().require(CatalogComponent.class);
        var object = manager.create();
        object.attach(catalog);
        return object;
    }
}
