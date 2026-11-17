package dev.sixik.sdmal.content;

import dev.sixik.sdmal.core.content.ContentRegistration;
import dev.sixik.sdmal.core.content.ContentRuntime;
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
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
//?} else {
/*import dev.sixik.sdmal.core.content.LegacyTabs;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
*///?}

/**
 * Fabric registry backend, installed automatically from the library's main initializer.
 */
public final class FabricContent implements ContentRegistration.Backend {
    /**
     * Creates the loader-owned backend.
     */
    private FabricContent() {}
    /**
     * Discovers all indexed content and attaches creative tab append hooks.
     */
    public static void initialize() {
        var registration = ContentRuntime.install(new FabricContent());
        //? if >=1.19.3 {
        for (var id : registration.referencedTabs()) {
            if (registration.ownsTab(id)) continue;
            ItemGroupEvents.modifyEntriesEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, key(id)))
                    .register(entries -> registration.appendTab(id, entries::accept));
        }
        //?}
    }
    /**
     * @param id native identifier
     * @return parsed native key
     */
    private static ResourceLocation key(String id) { return java.util.Objects.requireNonNull(ResourceLocation.tryParse(id)); }
    /**
     * @param id block id
     * @param factory prepared block constructor
     */
    @Override public void block(String id, Supplier<Block> factory) {
        //? if >=1.19.3 {
        Registry.register(BuiltInRegistries.BLOCK, key(id), factory.get());
        //?} else {
        /*Registry.register(Registry.BLOCK, key(id), factory.get());
        *///?}
    }
    /**
     * @param id item id
     * @param factory prepared item constructor
     */
    @Override public void item(String id, Supplier<Item> factory) {
        //? if >=1.19.3 {
        Registry.register(BuiltInRegistries.ITEM, key(id), factory.get());
        //?} else {
        /*Registry.register(Registry.ITEM, key(id), factory.get());
        *///?}
    }
    /**
     * @param id block entity type id
     * @param factory prepared type constructor
     */
    @Override public void blockEntity(String id, Supplier<BlockEntityType<?>> factory) {
        //? if >=1.19.3 {
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key(id), factory.get());
        //?} else {
        /*Registry.register(Registry.BLOCK_ENTITY_TYPE, key(id), factory.get());
        *///?}
    }
    /**
     * @param id creative tab id
     * @param factory prepared tab constructor
     */
    @Override public void tab(String id, Supplier<CreativeModeTab> factory) {
        //? if >=1.19.3 {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, key(id), factory.get());
        //?} else {
        /*factory.get();
        *///?}
    }
    /**
     * @param id referenced tab id
     * @return native existing tab or null
     */
    @Override public CreativeModeTab existingTab(String id) {
        //? if >=1.19.3 {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(key(id)).orElse(null);
        //?} else {
        /*return LegacyTabs.find(id);
        *///?}
    }
    /**
     * @param registration registered tab metadata
     * @param id tab id
     * @return unregistered tab
     */
    @Override public CreativeModeTab createTab(ContentRegistration registration, String id) {
        //? if >=1.19.3 {
        return FabricItemGroup.builder().title(registration.title(id)).icon(() -> registration.icon(id))
                .displayItems((parameters, output) -> registration.appendTab(id, output::accept)).build();
        //?} else {
        /*return FabricItemGroupBuilder.create(key(id)).icon(() -> registration.icon(id))
                .appendItems(items -> registration.appendTab(id, items::add)).build();
        *///?}
    }
    /**
     * @param factory native entity factory
     * @param block valid host
     * @return unregistered type
     */
    @Override public BlockEntityType<?> createBlockEntityType(BiFunction<BlockPos, BlockState, BlockEntity> factory, Block block) {
        return FabricBlockEntityTypeBuilder.create(factory::apply, block).build();
    }
}
