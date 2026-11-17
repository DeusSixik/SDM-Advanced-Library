package dev.sixik.sdmal.example.ecs.multiblock;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import java.util.function.Supplier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
//? if >=1.19.3 {
import net.minecraft.world.level.material.MapColor;
//?} else {
/*import net.minecraft.world.level.material.Material;
*///?}
//? if >=1.21.2 {
/*import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
*///?}

/**
 * Shared properties for the playable multiblock controller; loader entrypoints own registration.
 */
public final class MultiblockContent {
    /** Stable block, item and block entity registry path in the sdmal namespace. */
    public static final String PATH = "ecs_multiblock_controller";

    /** Prevents construction of the shared content factory. */
    private MultiblockContent() {}

    /**
     * Creates a controller with its type resolved only after registry publication.
     * @param type supplier of its registered shell type
     * @return unregistered controller block
     */
    public static MultiblockControllerBlock createBlock(Supplier<? extends BlockEntityType<?>> type) {
        //? if >=1.19.3 {
        var properties = BlockBehaviour.Properties.of().mapColor(MapColor.METAL);
        //?} else {
        /*var properties = BlockBehaviour.Properties.of(Material.METAL);
        *///?}
        properties.strength(3.0f);
        //? if >=1.21.2
        //properties.setId(ResourceKey.create(Registries.BLOCK, SDMAdvancedLibrary.id(PATH)));
        return new MultiblockControllerBlock(properties, type);
    }

    /**
     * Creates the controller's placeable inventory item.
     * @param block registered controller block
     * @param legacyTab creative tab for 1.19.2; ignored on newer versions
     * @return unregistered block item
     */
    public static BlockItem createItem(Block block, CreativeModeTab legacyTab) {
        var properties = new Item.Properties();
        //? if <1.19.3
        //properties.tab(legacyTab);
        //? if >=1.21.2
        //properties.setId(ResourceKey.create(Registries.ITEM, SDMAdvancedLibrary.id(PATH)));
        return new BlockItem(block, properties);
    }
}
