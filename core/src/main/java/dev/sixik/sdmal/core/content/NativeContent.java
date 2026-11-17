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
        };
        //?} else {
        /*boolean occupied = switch (kind) {
            case BLOCK -> Registry.BLOCK.containsKey(key);
            case ITEM -> Registry.ITEM.containsKey(key);
            case BLOCK_ENTITY -> Registry.BLOCK_ENTITY_TYPE.containsKey(key);
            case TAB -> false;
        };
        *///?}
        if (occupied) throw new IllegalStateException("Native " + kind + " id is already registered: " + id);
    }
}
