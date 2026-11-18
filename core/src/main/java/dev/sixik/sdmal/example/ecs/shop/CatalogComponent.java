package dev.sixik.sdmal.example.ecs.shop;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import java.util.Objects;

/** World-independent shop metadata with automatic persistence and optional binary snapshots. */
@EcsComponent(id = "sdmal_example:shop_catalog")
public final class CatalogComponent extends Component {
    /** Non-blank application catalog key; independent of the owning object's UUID. */
    @EcsPersist("catalog_id")
    @EcsSync
    private String catalogId = "default";

    /** Relative catalog display order; lower values are shown first. */
    @EcsPersist("order")
    @EcsSync
    private int order;

    /** Creates default data for automatic discovery and archive restoration. */
    public CatalogComponent() {}

    /**
     * Creates metadata without requiring a world or game entity.
     * @param catalogId non-blank application key
     * @param order relative display order
     * @throws IllegalArgumentException if the catalog key is blank
     */
    public CatalogComponent(String catalogId, int order) {
        rename(catalogId);
        this.order = order;
    }

    /** @return application catalog key; read on the owning manager's thread */
    public String catalogId() {
        checkOwnerThread();
        return catalogId;
    }

    /** @return relative display order; read on the owning manager's thread */
    public int order() {
        checkOwnerThread();
        return order;
    }

    /**
     * Updates catalog identity on the owning manager's thread.
     * @param catalogId non-blank application key
     * @throws IllegalArgumentException if the key is blank
     * @throws IllegalStateException if called outside the owning thread or during worker compute
     */
    public void rename(String catalogId) {
        checkOwnerThread();
        Objects.requireNonNull(catalogId, "catalogId");
        if (catalogId.isBlank()) throw new IllegalArgumentException("Catalog id must not be blank");
        this.catalogId = catalogId;
    }
}
