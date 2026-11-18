package dev.sixik.sdmal.example.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sixik.sdmal.api.repository.RepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;

import java.util.UUID;

/**
 * Custom JSON codec with an explicit version and a small migration from an older property name.
 * It uses the same API for files, SQL and MongoDB; no backend needs to know the payload schema.
 */
public final class VersionedShopCodec implements RepositoryCodec<ShopRecord> {
    /**
     * Alternative definition for the versioned_shops collection in the local walkthrough.
     */
    public static final RepositoryDefinition<UUID, ShopRecord> DEFINITION = new RepositoryDefinition<>(
            UUID::toString, UUID::fromString, ShopRecord::id, new VersionedShopCodec());

    /**
     * Creates a stateless example codec.
     */
    public VersionedShopCodec() {}

    /**
     * Encodes the current schema with stable property names.
     * @param value detached shop record
     * @return version-one JSON document
     */
    @Override
    public String encode(ShopRecord value) {
        JsonObject json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("id", value.id().toString());
        json.addProperty("catalog_id", value.catalogId());
        json.addProperty("order", value.order());
        return json.toString();
    }

    /**
     * Reads the current schema or migrates version zero's catalog property.
     * @param encoded versioned JSON document
     * @return current immutable shop record
     * @throws RepositoryException for malformed data or an unsupported future version
     */
    @Override
    public ShopRecord decode(String encoded) {
        try {
            JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
            int version = json.has("version") ? json.get("version").getAsBigDecimal().intValueExact() : 0;
            if (version < 0 || version > 1) throw new RepositoryException("Unsupported shop data version: " + version);
            String catalogKey = version == 0 ? "catalog" : "catalog_id";
            return new ShopRecord(
                    UUID.fromString(json.get("id").getAsString()),
                    json.get(catalogKey).getAsString(),
                    json.get("order").getAsBigDecimal().intValueExact());
        } catch (RepositoryException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RepositoryException("Invalid versioned shop document", failure);
        }
    }
}
