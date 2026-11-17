package dev.sixik.sdmal;

import dev.sixik.sdmal.example.ecs.blockentity.GeneratorContent;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
//? if >=1.21 {
/*import net.minecraftforge.common.NeoForge;
*///?} else {
import net.minecraftforge.common.MinecraftForge;
//?}
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
//? if >=1.21 {
/*import net.minecraftforge.fml.ModContainer;
*///?} else {
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//?}
import java.util.function.Supplier;

/** Registers common content with both the legacy and modern MinecraftForge APIs. */
@Mod(SDMAdvancedLibrary.MODID)
public final class NeoForgeEntrypoint {
    /** Deferred block registry in the mod namespace. */
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, SDMAdvancedLibrary.MODID);
    /** Deferred item registry in the mod namespace. */
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SDMAdvancedLibrary.MODID);
    /** Deferred shell types for chunk-owned ECS objects. */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SDMAdvancedLibrary.MODID);
    /** Deferred creative tab registry. */
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SDMAdvancedLibrary.MODID);
    /** Registered example stone block. */
    public static final Supplier<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", SDMAdvancedLibrary::createBlock);
    /** Placeable ECS generator block. */
    public static final Supplier<Block> ECS_GENERATOR = BLOCKS.register(GeneratorContent.PATH,
        () -> GeneratorContent.createBlock(() -> NeoForgeEntrypoint.ECS_GENERATOR_TYPE.get()));
    /** Generator shell type; initialized by the block entity registry event. */
    public static final Supplier<BlockEntityType<GeneratorBlockEntity>> ECS_GENERATOR_TYPE =
        BLOCK_ENTITIES.register(GeneratorContent.PATH, NeoForgeEntrypoint::createGeneratorType);
    /** Generator item for creative inventory and /give. */
    public static final Supplier<Item> ECS_GENERATOR_ITEM = ITEMS.register(GeneratorContent.PATH,
        () -> GeneratorContent.createItem(ECS_GENERATOR.get(), null));
    /** Registered item representing the example block. */
    public static final Supplier<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> SDMAdvancedLibrary.createBlockItem(EXAMPLE_BLOCK.get(), null));
    /** Registered example food item. */
    public static final Supplier<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> SDMAdvancedLibrary.createFoodItem(null));
    /** Example creative tab containing the food item. */
    public static final Supplier<CreativeModeTab> EXAMPLE_TAB = TABS.register("example_tab", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.sdmal.example_tab"))
        .withTabsBefore(CreativeModeTabs.COMBAT)
        .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
        .displayItems((parameters, output) -> {
            output.accept(EXAMPLE_ITEM.get());
            output.accept(ECS_GENERATOR_ITEM.get());
        }).build());

    /**
     * Connects content and lifecycle listeners to MinecraftForge.
     * @param bus injected mod event bus on modern MinecraftForge
     * @param container injected mod container on modern MinecraftForge
     */
    //? if >=1.21 {
    /*public NeoForgeEntrypoint(IEventBus bus, ModContainer container) {
    *///?} else {
    public NeoForgeEntrypoint() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
    //?}
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
        bus.addListener(this::commonSetup);
        bus.addListener(NeoForgeEntrypoint::addCreative);
        bus.addListener(NeoForgeConfig::onLoad);
        //? if >=1.21 {
        /*NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> SDMAdvancedLibrary.LOGGER.info("HELLO from server starting"));
        *///?} else {
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> SDMAdvancedLibrary.LOGGER.info("HELLO from server starting"));
        //?}
        //? if >=26.3 {
        /*container.registerConfig(ModConfig.Type.LOCAL, NeoForgeConfig.SPEC, SDMAdvancedLibrary.MODID + "-common.toml");
        *///?} else if >=1.21 {
        /*container.registerConfig(ModConfig.Type.COMMON, NeoForgeConfig.SPEC);
        *///?} else {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NeoForgeConfig.SPEC);
        //?}
    }

    /**
     * Runs shared initialization after the native configuration has loaded.
     * @param event common setup lifecycle event
     */
    private void commonSetup(FMLCommonSetupEvent event) { SDMAdvancedLibrary.commonSetup(NeoForgeConfig.current); }

    /**
     * Builds the shell type using the constructor/builder provided by this loader version.
     * @return unregistered type accepting only the generator block
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
     * Adds the example block item to the building blocks tab.
     * @param event creative tab population event
     */
    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) event.accept(EXAMPLE_BLOCK_ITEM.get());
    }
}
