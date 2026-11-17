package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.api.content.ContentTab;
import dev.sixik.sdmal.api.content.RegisteredEntityBlock;
import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Coordinates validated automatic entries with the loader's native registry lifecycle.
 */
@ApiStatus.Internal
public final class ContentRegistration {
    /**
     * Loader-owned native registry operations.
     */
    public interface Backend {
        /**
         * @param id block id
         * @param factory deferred block constructor
         */
        void block(String id, Supplier<Block> factory);
        /**
         * @param id item id
         * @param factory deferred item constructor
         */
        void item(String id, Supplier<Item> factory);
        /**
         * @param id native block entity type id
         * @param factory deferred type constructor
         */
        void blockEntity(String id, Supplier<BlockEntityType<?>> factory);
        /**
         * @param id creative tab id
         * @param factory deferred native tab constructor
         */
        void tab(String id, Supplier<CreativeModeTab> factory);
        /**
         * @param id tab identifier
         * @return existing native tab or null
         */
        CreativeModeTab existingTab(String id);
        /**
         * @param registration source metadata
         * @param id owned tab id
         * @return unregistered native tab
         */
        CreativeModeTab createTab(ContentRegistration registration, String id);
        /**
         * @param factory native block entity factory
         * @param block valid host block
         * @return unregistered type
         */
        BlockEntityType<?> createBlockEntityType(BiFunction<BlockPos, BlockState, BlockEntity> factory, Block block);
    }
    /**
     * Validated cross-mod declaration metadata.
     */
    private final ContentPlan plan;
    /**
     * Prepared factories and progressively bound native instances.
     */
    private final Map<String, Entry> entries = new java.util.TreeMap<>();
    /**
     * Native block identity to its associated block entity factory.
     */
    private final Map<Block, Entry> byBlock = new IdentityHashMap<>();
    /**
     * Item identity to its ordered creative presentation.
     */
    private final Map<Item, String> itemTabs = new IdentityHashMap<>();
    /**
     * Native tabs, including existing referenced vanilla tabs.
     */
    private final Map<String, CreativeModeTab> tabs = new HashMap<>();
    /**
     * Customization objects for owned tabs, absent for default tabs.
     */
    private final Map<String, ContentTab> tabConfigurations = new HashMap<>();
    /**
     * Native tabs created by this registration, as opposed to append-only references.
     */
    private final java.util.Set<String> ownedTabs = new java.util.HashSet<>();
    /**
     * Whether this plan has already been attached to a native lifecycle.
     */
    private boolean enlisted;

    /**
     * Validates all constructors before publishing any entry to native registries.
     * @param types all indexed implementation classes
     */
    public ContentRegistration(Collection<Class<?>> types) {
        plan = new ContentPlan(types);
        for (var group : plan.groups.values()) entries.put(group.id, new Entry(group));
    }
    /**
     * Enlists default/custom tabs, blocks, items and block entity types with the selected loader.
     * @param backend native backend; registration order is owned by the loader
     */
    public void enlist(Backend backend) {
        if (enlisted) throw new IllegalStateException("Content is already enlisted");
        enlisted = true;
        var tabIds = new TreeSet<>(plan.entries.keySet());
        for (var entry : entries.values()) if (entry.group.get(ContentKind.TAB) != null) tabIds.add(entry.group.id);
        for (var id : tabIds) {
            var entry = entries.get(id);
            boolean customized = entry != null && entry.group.get(ContentKind.TAB) != null;
            var existing = backend.existingTab(id);
            if (existing != null) {
                if (customized) throw new IllegalStateException("Cannot replace existing creative tab: " + id);
                tabs.put(id, existing);
                continue;
            }
            if (id.startsWith("minecraft:")) throw new IllegalArgumentException("Unknown vanilla creative tab: " + id);
            ownedTabs.add(id);
            backend.tab(id, () -> {
                NativeContent.requireVacant(ContentKind.TAB, id);
                if (customized) {
                    try { tabConfigurations.put(id, (ContentTab) entry.tabConstructor.invokeExact()); }
                    catch (Throwable failure) { throw ContentConstructors.failure(id, failure); }
                }
                var tab = Objects.requireNonNull(backend.createTab(this, id), "Native tab");
                tabs.put(id, tab);
                return tab;
            });
        }
        for (var entry : entries.values()) {
            String id = entry.group.id;
            if (entry.group.hasBlock()) backend.block(id, () -> createBlock(entry));
            if (entry.group.hasItem()) backend.item(id, () -> createItem(entry));
            if (entry.entityConstructor != null) backend.blockEntity(id, () -> {
                NativeContent.requireVacant(ContentKind.BLOCK_ENTITY, id);
                var type = Objects.requireNonNull(backend.createBlockEntityType((position, state) -> entry.createEntity(position, state), block(id)), "Native block entity type");
                entry.entityType = type;
                return type;
            });
        }
    }
    /**
     * @param entry block group
     * @return newly created unregistered block
     */
    private Block createBlock(Entry entry) {
        String id = entry.group.id;
        NativeContent.requireVacant(ContentKind.BLOCK, id);
        var properties = NativeContent.blockProperties(id);
        try {
            Block block = entry.blockConstructor == null ? new RegisteredEntityBlock(properties)
                    : (Block) entry.blockConstructor.invokeExact(properties);
            entry.block = block;
            byBlock.put(block, entry);
            return block;
        } catch (Throwable failure) { throw ContentConstructors.failure(id, failure); }
    }
    /**
     * @param entry item group
     * @return newly created unregistered inventory item
     */
    private Item createItem(Entry entry) {
        String id = entry.group.id;
        NativeContent.requireVacant(ContentKind.ITEM, id);
        //? if <1.19.3 {
        /*var legacyTab = entry.group.tab.isEmpty() ? null : tab(entry.group.tab);
        *///?} else {
        CreativeModeTab legacyTab = null;
        //?}
        boolean blockItem = entry.itemConstructor == null || BlockItem.class.isAssignableFrom(entry.group.get(ContentKind.ITEM).type());
        var properties = NativeContent.itemProperties(id, legacyTab, blockItem);
        try {
            Item item;
            if (entry.itemConstructor == null) item = new BlockItem(block(id), properties);
            else if (BlockItem.class.isAssignableFrom(entry.group.get(ContentKind.ITEM).type()))
                item = (Item) entry.itemConstructor.invokeExact(block(id), properties);
            else item = (Item) entry.itemConstructor.invokeExact(properties);
            entry.item = item;
            if (!entry.group.tab.isEmpty()) itemTabs.put(item, entry.group.tab);
            return item;
        } catch (Throwable failure) { throw ContentConstructors.failure(id, failure); }
    }
    /**
     * @param type annotated class
     * @return validated registry id
     */
    public String id(Class<?> type) {
        var id = plan.classes.get(type);
        if (id == null) throw new IllegalStateException("Class is not indexed as content: " + type.getName());
        return id;
    }
    /**
     * @param id automatic block id
     * @return constructed block after its registry event
     */
    public Block block(String id) { return bound(entry(id).block, "Block", id); }
    /**
     * @param id automatic item id
     * @return constructed item after its registry event
     */
    public Item item(String id) { return bound(entry(id).item, "Item", id); }
    /**
     * @param id native entity type id
     * @return constructed type after its registry event
     */
    public BlockEntityType<?> blockEntityType(String id) { return bound(entry(id).entityType, "BlockEntityType", id); }
    /**
     * @param id native tab id
     * @return registered/created referenced tab
     */
    public CreativeModeTab tab(String id) { return bound(tabs.get(id), "CreativeModeTab", id); }
    /**
     * @param id native tab id
     * @return whether this plan constructs the tab
     */
    public boolean ownsTab(String id) { return ownedTabs.contains(id); }
    /**
     * @return tab ids requiring ordered content append hooks
     */
    public java.util.Set<String> referencedTabs() { return java.util.Collections.unmodifiableSet(plan.entries.keySet()); }
    /**
     * @param id tab id
     * @return custom or default translatable title
     */
    public Component title(String id) {
        var config = tabConfigurations.get(id);
        var title = config == null ? null : config.title();
        return title == null ? Component.translatable("itemGroup." + id.replace(':', '.')) : title;
    }
    /**
     * @param id tab id
     * @return nonempty icon stack from customization or first sorted item
     */
    public ItemStack icon(String id) {
        var config = tabConfigurations.get(id);
        var icon = config == null ? ItemStack.EMPTY : Objects.requireNonNull(config.icon(), "Tab icon");
        if (!icon.isEmpty()) return icon;
        var groups = plan.entries.get(id);
        return groups == null || groups.length == 0 ? Items.CHEST.getDefaultInstance() : item(groups[0].id).getDefaultInstance();
    }
    /**
     * Emits annotated items in ascending ordinal/id order, followed by custom tab extras.
     * @param id target tab id
     * @param output native stack consumer
     */
    public void appendTab(String id, Consumer<ItemStack> output) {
        var groups = plan.entries.get(id);
        if (groups != null) for (int index = 0; index < groups.length; index++) output.accept(item(groups[index].id).getDefaultInstance());
        var config = tabConfigurations.get(id);
        if (config != null) config.appendItems(output);
    }
    /**
     * @param tab native legacy tab
     * @param stack existing native stack
     * @return whether it belongs to this automatic tab group
     */
    boolean isAutomaticEntry(CreativeModeTab tab, ItemStack stack) {
        String id = itemTabs.get(stack.getItem());
        return id != null && tabs.get(id) == tab;
    }
    /**
     * @param tab native legacy tab
     * @return referenced tab id, or null
     */
    String tabId(CreativeModeTab tab) {
        for (var entry : tabs.entrySet()) if (entry.getValue() == tab) return entry.getKey();
        return null;
    }
    /**
     * @param block registered host
     * @param position world coordinate
     * @param state current state
     * @return new associated native entity
     */
    public BlockEntity newBlockEntity(Block block, BlockPos position, BlockState state) {
        var entry = byBlock.get(block);
        if (entry == null || entry.entityConstructor == null) throw new IllegalStateException("No annotated BlockEntity associated with " + block);
        return entry.createEntity(position, state);
    }
    /**
     * @param id indexed group identifier
     * @return known declaration group
     */
    private Entry entry(String id) {
        var entry = entries.get(id);
        if (entry == null) throw new IllegalStateException("Unknown automatic content: " + id);
        return entry;
    }
    /**
     * @param value bound native value or null
     * @param kind registry category
     * @param id requested id
     * @param <T> native type
     * @return bound non-null value
     */
    private static <T> T bound(T value, String kind, String id) {
        if (value == null) throw new IllegalStateException(kind + " registration has not completed: " + id);
        return value;
    }
    /**
     * Prepared startup handles and native values for one registry id.
     */
    private static final class Entry {
        /**
         * Effective declaration metadata.
         */
        private final ContentPlan.Group group;
        /**
         * Explicit block properties constructor, or null for generated blocks.
         */
        private final MethodHandle blockConstructor;
        /**
         * Explicit item constructor, or null for generated block items.
         */
        private final MethodHandle itemConstructor;
        /**
         * Uniform type/position/state entity factory, or null.
         */
        private final MethodHandle entityConstructor;
        /**
         * Noarg custom tab configuration constructor, or null.
         */
        private final MethodHandle tabConstructor;
        /**
         * Bound native block after block registration.
         */
        private Block block;
        /**
         * Bound native item after item registration.
         */
        private Item item;
        /**
         * Bound native type after its registry event.
         */
        private BlockEntityType<?> entityType;
        /**
         * @param group validated metadata
         */
        private Entry(ContentPlan.Group group) {
            this.group = group;
            blockConstructor = ContentConstructors.resolve(group, ContentKind.BLOCK);
            itemConstructor = ContentConstructors.resolve(group, ContentKind.ITEM);
            entityConstructor = ContentConstructors.resolve(group, ContentKind.BLOCK_ENTITY);
            tabConstructor = ContentConstructors.resolve(group, ContentKind.TAB);
        }
        /**
         * @param position world coordinate
         * @param state block state
         * @return a new native entity
         */
        private BlockEntity createEntity(BlockPos position, BlockState state) {
            if (entityType == null) throw new IllegalStateException("Block entity type is not registered: " + group.id);
            try { return (BlockEntity) entityConstructor.invokeExact((BlockEntityType) entityType, position, state); }
            catch (Throwable failure) { throw ContentConstructors.failure(group.id, failure); }
        }
    }
}
