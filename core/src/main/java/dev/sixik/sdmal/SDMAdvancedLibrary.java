package dev.sixik.sdmal;

import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;

//? if >=1.19.3 {

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
//?} else {
/*import net.minecraft.core.Registry;
import net.minecraft.world.level.material.Material;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
//? if >=1.19.3
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared mod identity, content factories and initialization for every loader.
 */
public final class SDMAdvancedLibrary {
    /**
     * Namespace used by all registry entries and configuration files.
     */
    public static final String MODID = "sdmal";
    /**
     * Logger shared by the loader entry points.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    /**
     * Prevents instantiation of the shared utility class.
     */
    private SDMAdvancedLibrary() {
    }

    /**
     * Creates a namespaced registry identifier.
     *
     * @param path lowercase registry path within the mod namespace
     * @return non-null identifier
     */
    public static ResourceLocation id(String path) {
        //? if >=1.21 {
        /*return ResourceLocation.fromNamespaceAndPath(MODID, path);
         *///?} else {
        return new ResourceLocation(MODID, path);
        //?}
    }

    /**
     * Creates the example stone block, including the registry key required since 1.21.2.
     *
     * @return unregistered block
     */
    public static Block createBlock() {
        //? if >=1.19.3 {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().mapColor(MapColor.STONE);
        //?} else {
        /*BlockBehaviour.Properties properties = BlockBehaviour.Properties.of(Material.STONE);
         *///?}
        //? if >=1.21.2
        //properties.setId(ResourceKey.create(Registries.BLOCK, id("example_block")));
        return new Block(properties);
    }

    /**
     * Creates the item representing the example block.
     *
     * @param block     registered example block
     * @param legacyTab creative tab for 1.19.2, ignored on later versions; may be null
     * @return unregistered block item
     */
    public static BlockItem createBlockItem(Block block, CreativeModeTab legacyTab) {
        Item.Properties properties = itemProperties("example_block");
        //? if <1.19.3
        //properties.tab(legacyTab);
        return new BlockItem(block, properties);
    }

    /**
     * Creates the example food with nutrition 1 and saturation modifier 2.
     *
     * @param legacyTab creative tab for 1.19.2, ignored on later versions; may be null
     * @return unregistered food item
     */
    public static Item createFoodItem(CreativeModeTab legacyTab) {
        FoodProperties.Builder food = new FoodProperties.Builder().nutrition(1);
        //? if >=1.21 {
        /*food.alwaysEdible().saturationModifier(2f);
         *///?} else {
        food.alwaysEat().saturationMod(2f);
        //?}
        Item.Properties properties = itemProperties("example_item").food(food.build());
        //? if <1.19.3
        //properties.tab(legacyTab);
        return new Item(properties);
    }

    /**
     * Creates item properties with a registry key on versions that require it.
     *
     * @param path lowercase item registry path
     * @return non-null properties
     */
    private static Item.Properties itemProperties(String path) {
        Item.Properties properties = new Item.Properties();
        //? if >=1.21.2
        //properties.setId(ResourceKey.create(Registries.ITEM, id(path)));
        return properties;
    }

    /**
     * Checks whether a configured identifier denotes a registered item.
     *
     * @param value candidate item identifier
     * @return true only for syntactically valid registered identifiers
     */
    public static boolean isItem(Object value) {
        if (!(value instanceof String name)) return false;
        ResourceLocation id = ResourceLocation.tryParse(name);
        //? if >=1.19.3 {
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
        //?} else {
        /*return id != null && Registry.ITEM.containsKey(id);
         *///?}
    }

    /**
     * Logs the shared setup messages using a loader-provided configuration.
     *
     * @param config validated configuration values
     */
    public static void commonSetup(Config config) {
        WorldObjectComponentRegistry.instance().registerServices();
        LOGGER.info("HELLO FROM COMMON SETUP");
        if (config.logDirtBlock()) {
            //? if >=1.19.3 {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
            //?} else {
            /*LOGGER.info("DIRT BLOCK >> {}", Registry.BLOCK.getKey(Blocks.DIRT));
             *///?}
        }
        LOGGER.info("{}{}", config.magicNumberIntroduction(), config.magicNumber());
        config.items().stream().filter(SDMAdvancedLibrary::isItem)
                .forEach(item -> LOGGER.info("ITEM >> {}", item));
    }
}
