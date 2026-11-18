package dev.sixik.sdmal.core.content;

//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
import net.minecraft.world.level.material.Material;
*///?}
//? if >=1.21.2 {
/*import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.state.BlockBehaviour;
import dev.sixik.sdmal.api.content.annotations.EntitySettings;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
//? if >=1.19.3
import net.minecraft.world.level.material.MapColor;

/**
 * Version-specific native keys and properties, prepared before content constructors run.
 */
final class NativeContent {
    /**
     * Prevents utility construction.
     */
    private NativeContent() {}
    /**
     * @param id validated namespaced id
     * @return native identifier
     */
    static ResourceLocation id(String id) { return java.util.Objects.requireNonNull(ResourceLocation.tryParse(id), "Invalid content id: " + id); }
    /**
     * @param id registered block id
     * @return keyed default stone-like properties
     */
    static BlockBehaviour.Properties blockProperties(String id) {
        //? if >=1.19.3 {
        var properties = BlockBehaviour.Properties.of().mapColor(MapColor.STONE);
        //?} else {
        /*var properties = BlockBehaviour.Properties.of(Material.STONE);
        *///?}
        properties.strength(1.5f);
        //? if >=1.21.2
        //properties.setId(ResourceKey.create(Registries.BLOCK, id(id)));
        return properties;
    }
    /**
     * @param id registered item id
     * @param tab legacy creative tab or null
     * @param blockItem whether block translation keys should be used
     * @return keyed native properties
     */
    static Item.Properties itemProperties(String id, CreativeModeTab tab, boolean blockItem) {
        var properties = new Item.Properties();
        //? if <1.19.3
        //properties.tab(tab);
        //? if >=1.21.2
        //properties.setId(ResourceKey.create(Registries.ITEM, id(id)));
        //? if >=1.21.2
        //if (blockItem) properties.useBlockDescriptionPrefix();
        return properties;
    }
    /**
     * Rejects collisions with existing native entries instead of silently replacing another mod's content.
     * @param kind native registry kind
     * @param id planned registry identifier
     */
    static void requireVacant(ContentKind kind, String id) {
        var key = id(id);
        //? if >=1.19.3 {
        boolean occupied = switch (kind) {
            case BLOCK -> BuiltInRegistries.BLOCK.containsKey(key);
            case ITEM -> BuiltInRegistries.ITEM.containsKey(key);
            case BLOCK_ENTITY -> BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(key);
            case TAB -> BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(key);
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE.containsKey(key);
            case MENU -> BuiltInRegistries.MENU.containsKey(key);
        };
        //?} else {
        /*boolean occupied = switch (kind) {
            case BLOCK -> Registry.BLOCK.containsKey(key);
            case ITEM -> Registry.ITEM.containsKey(key);
            case BLOCK_ENTITY -> Registry.BLOCK_ENTITY_TYPE.containsKey(key);
            case TAB -> false;
            case ENTITY -> Registry.ENTITY_TYPE.containsKey(key);
            case MENU -> Registry.MENU.containsKey(key);
        };
        *///?}
        if (occupied) throw new IllegalStateException("Native " + kind + " id is already registered: " + id);
    }

    /**
     * Validates optional type settings before any registry factory runs.
     *
     * @param settings declared settings, or null for defaults
     */
    static void validateEntitySettings(EntitySettings settings) {
        if (settings == null) {
            return;
        }
        if (!Float.isFinite(settings.width()) || settings.width() <= 0
                || !Float.isFinite(settings.height()) || settings.height() <= 0
                || settings.trackingRange() < 0 || settings.updateInterval() <= 0) {
            throw new IllegalArgumentException("Entity dimensions must be finite and positive; tracking range >= 0, interval > 0");
        }
    }

    /**
     * Creates the native type with a cached constructor factory and optional settings.
     *
     * @param id registry identity
     * @param factory native world entity factory
     * @param settings optional dimensions and tracking settings
     * @return unregistered entity type
     */
    static EntityType<?> entityType(String id, EntityType.EntityFactory<Entity> factory, EntitySettings settings) {
        var builder = EntityType.Builder.of(factory, settings == null ? MobCategory.MISC : settings.category())
                .sized(settings == null ? 0.6f : settings.width(), settings == null ? 1.8f : settings.height())
                .clientTrackingRange(settings == null ? 5 : settings.trackingRange())
                .updateInterval(settings == null ? 3 : settings.updateInterval());
        if (settings != null && settings.fireImmune()) {
            builder.fireImmune();
        }
        //? if >=1.21.2 {
        /*return builder.build(ResourceKey.create(Registries.ENTITY_TYPE, id(id)));
        *///?} else {
        return builder.build(id);
        //?}
    }
}
