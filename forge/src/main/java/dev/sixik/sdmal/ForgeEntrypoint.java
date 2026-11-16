package dev.sixik.sdmal;

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

/** Registers the shared content and configuration with Forge. */
@Mod(SDMAdvancedLibrary.MODID)
public final class ForgeEntrypoint {
    /** Deferred block registry in the mod namespace. */
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, SDMAdvancedLibrary.MODID);
    /** Deferred item registry in the mod namespace. */
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SDMAdvancedLibrary.MODID);
    /** Legacy creative tab, populated through item properties in Minecraft 1.19.2. */
    //? if <1.19.3 {
    /*public static final CreativeModeTab LEGACY_TAB = new CreativeModeTab(SDMAdvancedLibrary.MODID) {
        @Override public ItemStack makeIcon() { return EXAMPLE_ITEM.get().getDefaultInstance(); }
    };
    *///?}
    /** Registered example stone block. */
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", SDMAdvancedLibrary::createBlock);
    /** Registered item representing the example block. */
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> SDMAdvancedLibrary.createBlockItem(EXAMPLE_BLOCK.get(),
        //? if <1.19.3 {
        /*LEGACY_TAB
        *///?} else {
        null
        //?}
    ));
    /** Registered example food item. */
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> SDMAdvancedLibrary.createFoodItem(
        //? if <1.19.3 {
        /*LEGACY_TAB
        *///?} else {
        null
        //?}
    ));
    //? if >=1.19.3 {
    /** Deferred creative tab registry, introduced in Minecraft 1.19.3. */
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SDMAdvancedLibrary.MODID);
    /** Example creative tab containing the food item. */
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = TABS.register("example_tab", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.sdmal.example_tab"))
        .withTabsBefore(CreativeModeTabs.COMBAT)
        .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
        .displayItems((parameters, output) -> output.accept(EXAMPLE_ITEM.get())).build());
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
    private void commonSetup(FMLCommonSetupEvent event) { SDMAdvancedLibrary.commonSetup(ForgeConfig.current); }

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
