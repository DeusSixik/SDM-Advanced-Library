package dev.sixik.sdmal;

import dev.sixik.sdmal.example.ecs.blockentity.GeneratorContent;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;
import dev.sixik.sdmal.example.ecs.multiblock.MultiblockContent;
import dev.sixik.sdmal.example.ecs.multiblock.MultiblockControllerBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
//? if >=1.19.3 {
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
//?} else {
/*import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
*///?}
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.io.IOException;

/** Registers the shared examples and configuration on Fabric. */
public final class FabricEntrypoint implements ModInitializer {
    /** Registered type assigned after the generator block, before any world can load. */
    private static BlockEntityType<GeneratorBlockEntity> generatorType;
    /** Registered multiblock controller shell type, assigned before worlds load. */
    private static BlockEntityType<MultiblockControllerBlockEntity> multiblockType;
    /** Public no-argument constructor used by Fabric Loader. */
    public FabricEntrypoint() {}

    /** Registers the mod content after Minecraft's built-in registries are bootstrapped. */
    @Override public void onInitialize() {
        //? if >=1.19.3 {
        var blockRegistry = BuiltInRegistries.BLOCK;
        var itemRegistry = BuiltInRegistries.ITEM;
        var blockEntityRegistry = BuiltInRegistries.BLOCK_ENTITY_TYPE;
        //?} else {
        /*var blockRegistry = Registry.BLOCK;
        var itemRegistry = Registry.ITEM;
        var blockEntityRegistry = Registry.BLOCK_ENTITY_TYPE;
        *///?}
        //? if <1.19.3 {
        /*CreativeModeTab legacyTab = FabricItemGroupBuilder.create(SDMAdvancedLibrary.id("example_tab"))
            .icon(() -> itemRegistry.get(SDMAdvancedLibrary.id("example_item")).getDefaultInstance()).build();
        *///?} else {
        CreativeModeTab legacyTab = null;
        //?}
        Block block = Registry.register(blockRegistry, SDMAdvancedLibrary.id("example_block"), SDMAdvancedLibrary.createBlock());
        Item blockItem = Registry.register(itemRegistry, SDMAdvancedLibrary.id("example_block"), SDMAdvancedLibrary.createBlockItem(block, legacyTab));
        Item food = Registry.register(itemRegistry, SDMAdvancedLibrary.id("example_item"), SDMAdvancedLibrary.createFoodItem(legacyTab));
        Block generator = Registry.register(blockRegistry, SDMAdvancedLibrary.id(GeneratorContent.PATH),
            GeneratorContent.createBlock(() -> generatorType));
        Item generatorItem = Registry.register(itemRegistry, SDMAdvancedLibrary.id(GeneratorContent.PATH),
            GeneratorContent.createItem(generator, legacyTab));
        generatorType = Registry.register(blockEntityRegistry, SDMAdvancedLibrary.id(GeneratorContent.PATH),
            FabricBlockEntityTypeBuilder.create(
                (pos, state) -> new GeneratorBlockEntity(generatorType, pos, state), generator).build());
        Block controller = Registry.register(blockRegistry, SDMAdvancedLibrary.id(MultiblockContent.PATH),
            MultiblockContent.createBlock(() -> multiblockType));
        Item controllerItem = Registry.register(itemRegistry, SDMAdvancedLibrary.id(MultiblockContent.PATH),
            MultiblockContent.createItem(controller, legacyTab));
        multiblockType = Registry.register(blockEntityRegistry, SDMAdvancedLibrary.id(MultiblockContent.PATH),
            FabricBlockEntityTypeBuilder.create(
                (pos, state) -> new MultiblockControllerBlockEntity(multiblockType, pos, state), controller).build());
        //? if >=1.19.3 {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, SDMAdvancedLibrary.id("example_tab"), FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.sdmal.example_tab"))
            .icon(food::getDefaultInstance)
            .displayItems((parameters, output) -> {
                output.accept(food);
                output.accept(generatorItem);
                output.accept(controllerItem);
            }).build());
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> entries.accept(blockItem));
        //?}
        try {
            SDMAdvancedLibrary.commonSetup(Config.load(FabricLoader.getInstance().getConfigDir().resolve("sdmal-common.json")));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load SDMAL configuration", e);
        }
        ServerLifecycleEvents.SERVER_STARTING.register(server -> SDMAdvancedLibrary.LOGGER.info("HELLO from server starting"));
    }
}
