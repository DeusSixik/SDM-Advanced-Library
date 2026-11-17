package dev.sixik.sdmal.content;

import dev.sixik.sdmal.core.content.ContentRegistration;
import dev.sixik.sdmal.core.content.ContentRuntime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.DeferredRegister;
//? if forge && >=26.1 {
/*import net.minecraftforge.eventbus.api.bus.BusGroup;
*///?} else {
import net.minecraftforge.eventbus.api.IEventBus;
//?}
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
//?} else {
/*import dev.sixik.sdmal.core.content.LegacyTabs;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
*///?}

/**
 * Forge-family lifecycle backend; registry groups are partitioned by the annotation's namespace.
 */
public final class FmlContent implements ContentRegistration.Backend {
    /**
     * Deferred block registries by namespace.
     */
    private final Map<String, DeferredRegister<Block>> blocks = new HashMap<>();
    /**
     * Deferred item registries by namespace.
     */
    private final Map<String, DeferredRegister<Item>> items = new HashMap<>();
    /**
     * Deferred block entity type registries by namespace.
     */
    private final Map<String, DeferredRegister<BlockEntityType<?>>> entities = new HashMap<>();
    //? if >=1.19.3 {
    /**
     * Deferred creative tab registries by namespace.
     */
    private final Map<String, DeferredRegister<CreativeModeTab>> tabs = new HashMap<>();
    //?}
    /**
     * Creates a loader-owned registry backend.
     */
    private FmlContent() {}
    /**
     * Enlists metadata during mod construction, before native RegisterEvents start.
     * @param bus library mod event bus
     */
    //? if forge && >=26.1 {
    /*public static void initialize(BusGroup bus) {
    *///?} else {
    public static void initialize(IEventBus bus) {
    //?}
        var backend = new FmlContent();
        var registration = ContentRuntime.install(backend);
        for (var registry : backend.blocks.values()) registry.register(bus);
        for (var registry : backend.items.values()) registry.register(bus);
        for (var registry : backend.entities.values()) registry.register(bus);
        //? if >=1.19.3 {
        for (var registry : backend.tabs.values()) registry.register(bus);
        //? if forge && >=26.1 {
        /*BuildCreativeModeTabContentsEvent.BUS.addListener(event -> append(registration, event));
        *///?} else {
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> append(registration, event));
        //?}
        //?}
    }
    //? if >=1.19.3 {
    /**
     * @param registration ordered entries
     * @param event native tab rebuild
     */
    private static void append(ContentRegistration registration, BuildCreativeModeTabContentsEvent event) {
        //? if >=1.21.11 {
        /*String id = event.getTabKey().identifier().toString();
        *///?} else {
        String id = event.getTabKey().location().toString();
        //?}
        if (registration.ownsTab(id)) return;
        registration.appendTab(id, event::accept);
    }
    //?}
    /**
     * @param id native registry id
     * @return parsed identifier
     */
    private static ResourceLocation key(String id) { return java.util.Objects.requireNonNull(ResourceLocation.tryParse(id)); }
    /**
     * @param id block id
     * @param factory deferred block constructor
     */
    @Override public void block(String id, Supplier<Block> factory) {
        var key = key(id);
        blocks.computeIfAbsent(key.getNamespace(), namespace -> DeferredRegister.create(
                //? if >=1.19.3 {
                Registries.BLOCK,
                //?} else {
                /*Registry.BLOCK_REGISTRY,
                *///?}
                namespace)).register(key.getPath(), factory);
    }
    /**
     * @param id item id
     * @param factory deferred item constructor
     */
    @Override public void item(String id, Supplier<Item> factory) {
        var key = key(id);
        items.computeIfAbsent(key.getNamespace(), namespace -> DeferredRegister.create(
                //? if >=1.19.3 {
                Registries.ITEM,
                //?} else {
                /*Registry.ITEM_REGISTRY,
                *///?}
                namespace)).register(key.getPath(), factory);
    }
    /**
     * @param id entity type id
     * @param factory deferred type constructor
     */
    @Override public void blockEntity(String id, Supplier<BlockEntityType<?>> factory) {
        var key = key(id);
        entities.computeIfAbsent(key.getNamespace(), namespace -> DeferredRegister.create(
                //? if >=1.19.3 {
                Registries.BLOCK_ENTITY_TYPE,
                //?} else {
                /*Registry.BLOCK_ENTITY_TYPE_REGISTRY,
                *///?}
                namespace)).register(key.getPath(), factory);
    }
    /**
     * @param id tab id
     * @param factory deferred tab constructor
     */
    @Override public void tab(String id, Supplier<CreativeModeTab> factory) {
        //? if >=1.19.3 {
        var key = key(id);
        tabs.computeIfAbsent(key.getNamespace(), namespace -> DeferredRegister.create(Registries.CREATIVE_MODE_TAB, namespace)).register(key.getPath(), factory);
        //?} else {
        /*factory.get();
        *///?}
    }
    /**
     * @param id referenced tab id
     * @return existing native tab or null
     */
    @Override public CreativeModeTab existingTab(String id) {
        //? if >=1.19.3 {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(key(id)).orElse(null);
        //?} else {
        /*return LegacyTabs.find(id);
        *///?}
    }
    /**
     * @param registration source metadata
     * @param id owned tab id
     * @return newly built native tab
     */
    @Override public CreativeModeTab createTab(ContentRegistration registration, String id) {
        //? if >=1.19.3 {
        return CreativeModeTab.builder().title(registration.title(id)).icon(() -> registration.icon(id))
                .displayItems((parameters, output) -> registration.appendTab(id, output::accept)).build();
        //?} else {
        /*return new CreativeModeTab(id.replace(':', '.')) {
            @Override public Component getDisplayName() { return registration.title(id); }
            @Override public ItemStack makeIcon() { return registration.icon(id); }
            @Override public void fillItemList(NonNullList<ItemStack> output) { registration.appendTab(id, output::add); }
        };
        *///?}
    }
    /**
     * @param factory cached native entity factory
     * @param block valid host
     * @return unregistered type
     */
    @Override public BlockEntityType<?> createBlockEntityType(BiFunction<BlockPos, BlockState, BlockEntity> factory, Block block) {
        //? if >=1.21.2 {
        /*return new BlockEntityType<>(factory::apply, java.util.Set.of(block));
        *///?} else {
        return BlockEntityType.Builder.of(factory::apply, block).build(null);
        //?}
    }
}
