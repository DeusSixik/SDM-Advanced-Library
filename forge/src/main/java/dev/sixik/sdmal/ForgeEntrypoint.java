package dev.sixik.sdmal;

import dev.sixik.sdmal.example.ecs.blockentity.GeneratorContent;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;
import dev.sixik.sdmal.example.ecs.multiblock.MultiblockContent;
import dev.sixik.sdmal.example.ecs.multiblock.MultiblockControllerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.Set;
//? if >=1.19.3 {
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
//?}
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers the shared content and configuration with Forge.
 */
@Mod(SDMAdvancedLibrary.MODID)
public final class ForgeEntrypoint {
    /**
     * Deferred block registry in the mod namespace.
     */
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, SDMAdvancedLibrary.MODID);
    /**
     * Deferred item registry in the mod namespace.
     */
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SDMAdvancedLibrary.MODID);
    /**
     * Deferred block entity registry for the world-backed ECS example.
     */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SDMAdvancedLibrary.MODID);
    /**
     * Legacy creative tab, populated through item properties in Minecraft 1.19.2.
     */
    //? if <1.19.3 {
    /*public static final CreativeModeTab LEGACY_TAB = new CreativeModeTab(SDMAdvancedLibrary.MODID) {
        @Override public ItemStack makeIcon() { return EXAMPLE_ITEM.get().getDefaultInstance(); }
    };
    *///?}
    /**
     * Registered example stone block.
     */
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", SDMAdvancedLibrary::createBlock);
    /**
     * Placeable generator driven by the ECS world scheduler.
     */
    public static final RegistryObject<Block> ECS_GENERATOR = BLOCKS.register(GeneratorContent.PATH,
        () -> GeneratorContent.createBlock(() -> ForgeEntrypoint.ECS_GENERATOR_TYPE.get()));
    /**
     * Registered shell type with the generator block as its only valid host.
     */
    public static final RegistryObject<BlockEntityType<GeneratorBlockEntity>> ECS_GENERATOR_TYPE =
        BLOCK_ENTITIES.register(GeneratorContent.PATH, ForgeEntrypoint::createGeneratorType);
    /**
     * Controller for the iron platform and redstone core multiblock example.
     */
    public static final RegistryObject<Block> ECS_MULTIBLOCK = BLOCKS.register(MultiblockContent.PATH,
        () -> MultiblockContent.createBlock(() -> ForgeEntrypoint.ECS_MULTIBLOCK_TYPE.get()));
    /**
     * Registered shell type for the multiblock controller.
     */
    public static final RegistryObject<BlockEntityType<MultiblockControllerBlockEntity>> ECS_MULTIBLOCK_TYPE =
        BLOCK_ENTITIES.register(MultiblockContent.PATH, ForgeEntrypoint::createMultiblockType);
    /**
     * Placeable multiblock controller item.
     */
    public static final RegistryObject<Item> ECS_MULTIBLOCK_ITEM = ITEMS.register(MultiblockContent.PATH,
        () -> MultiblockContent.createItem(ECS_MULTIBLOCK.get(),
            //? if <1.19.3 {
            /*LEGACY_TAB
            *///?} else {
            null
            //?}
        ));
    /**
     * Generator item for creative inventory and /give.
     */
    public static final RegistryObject<Item> ECS_GENERATOR_ITEM = ITEMS.register(GeneratorContent.PATH,
        () -> GeneratorContent.createItem(ECS_GENERATOR.get(),
            //? if <1.19.3 {
            /*LEGACY_TAB
            *///?} else {
            null
            //?}
        ));
    /**
     * Registered item representing the example block.
     */
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> SDMAdvancedLibrary.createBlockItem(EXAMPLE_BLOCK.get(),
        //? if <1.19.3 {
        /*LEGACY_TAB
        *///?} else {
        null
        //?}
    ));
    /**
     * Registered example food item.
     */
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> SDMAdvancedLibrary.createFoodItem(
        //? if <1.19.3 {
        /*LEGACY_TAB
        *///?} else {
        null
        //?}
    ));
    //? if >=1.19.3 {
    /**
     * Deferred creative tab registry, introduced in Minecraft 1.19.3.
     */
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SDMAdvancedLibrary.MODID);
    /**
     * Example creative tab containing the food item.
     */
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = TABS.register("example_tab", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.sdmal.example_tab"))
        .withTabsBefore(CreativeModeTabs.COMBAT)
        .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
        .displayItems((parameters, output) -> {
            output.accept(EXAMPLE_ITEM.get());
            output.accept(ECS_GENERATOR_ITEM.get());
            output.accept(ECS_MULTIBLOCK_ITEM.get());
        }).build());
    //?}

    /**
     * Connects registry and lifecycle listeners to the current Forge context.
     * @param context injected Forge loading context
     */
    //? if >=1.20 {
    public ForgeEntrypoint(FMLJavaModLoadingContext context) {
    //?} else {
    /*public ForgeEntrypoint() {
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
    *///?}
        //? if >=26.1 {
        /*var bus = context.getModBusGroup();
        FMLCommonSetupEvent.getBus(bus).addListener(this::commonSetup);
        BuildCreativeModeTabContentsEvent.BUS.addListener(ForgeEntrypoint::addCreative);
        ServerStartingEvent.BUS.addListener(event -> SDMAdvancedLibrary.LOGGER.info("HELLO from server starting"));
        *///?} else {
        var bus = context.getModEventBus();
        bus.addListener(this::commonSetup);
        //? if >=1.19.3
        bus.addListener(ForgeEntrypoint::addCreative);
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> SDMAdvancedLibrary.LOGGER.info("HELLO from server starting"));
        //?}
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        //? if >=1.19.3
        TABS.register(bus);
        //? if >=1.20 {
        context.registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);
        //?} else {
        /*net.minecraftforge.fml.ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeConfig.SPEC);
        *///?}
    }

    /**
     * Runs shared initialization after registries and configuration are loaded.
     * @param event common setup lifecycle event
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        dev.sixik.sdmal.network.ForgeNetwork.initialize();
        SDMAdvancedLibrary.commonSetup(ForgeConfig.current);
    }

    /**
     * Builds the type after its valid block is registered; factories resolve the type only on placement/load.
     * @return unregistered generator shell type
     */
    private static BlockEntityType<GeneratorBlockEntity> createGeneratorType() {
        //? if >=1.21.2 {
        /*return new BlockEntityType<>((pos, state) -> new GeneratorBlockEntity(ECS_GENERATOR_TYPE.get(), pos, state),
            Set.of(ECS_GENERATOR.get()));
        *///?} else {
        return BlockEntityType.Builder.of(
            (pos, state) -> new GeneratorBlockEntity(ECS_GENERATOR_TYPE.get(), pos, state), ECS_GENERATOR.get()).build(null);
        //?}
    }

    /**
     * Builds the controller type after its only valid block has been registered.
     * @return unregistered multiblock controller type
     */
    private static BlockEntityType<MultiblockControllerBlockEntity> createMultiblockType() {
        //? if >=1.21.2 {
        /*return new BlockEntityType<>((pos, state) -> new MultiblockControllerBlockEntity(ECS_MULTIBLOCK_TYPE.get(), pos, state),
            Set.of(ECS_MULTIBLOCK.get()));
        *///?} else {
        return BlockEntityType.Builder.of(
            (pos, state) -> new MultiblockControllerBlockEntity(ECS_MULTIBLOCK_TYPE.get(), pos, state), ECS_MULTIBLOCK.get()).build(null);
        //?}
    }

    //? if >=1.19.3 {
    /**
     * Adds the example block to the building blocks tab.
     * @param event creative tab population event
     */
    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) event.accept(EXAMPLE_BLOCK_ITEM.get());
    }
    //?}
}
