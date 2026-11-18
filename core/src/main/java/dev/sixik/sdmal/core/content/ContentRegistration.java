package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.api.content.ContentTab;
import dev.sixik.sdmal.api.content.RegisteredEntityBlock;
import dev.sixik.sdmal.api.content.annotations.EntitySettings;
import java.lang.invoke.MethodHandle;
import java.util.Collection;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Map;
import java.util.Objects;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * Coordinates validated automatic entries with the loader's native registry lifecycle.
 */
@ApiStatus.Internal
public final class ContentRegistration {
    /**
     * Native client menu factory without boxed window identifiers or loader-specific buffer types.
     */
    @FunctionalInterface
    public interface MenuFactory {
        /**
         * @param windowId native synchronization id
         * @param inventory local player inventory
         * @return newly constructed menu
         */
        AbstractContainerMenu create(int windowId, Inventory inventory);
    }
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
         * @param id world entity type id
         * @param factory deferred native type constructor
         */
        default void entity(String id, Supplier<EntityType<?>> factory) {
            throw new UnsupportedOperationException("Backend does not support EntityType");
        }
        /**
         * @param id container menu type id
         * @param factory deferred native type constructor
         */
        default void menu(String id, Supplier<MenuType<?>> factory) {
            throw new UnsupportedOperationException("Backend does not support MenuType");
        }
        /**
         * @param factory cached menu constructor bridge
         * @return unregistered native menu type using the loader's public extension API
         */
        default MenuType<?> createMenuType(MenuFactory factory) {
            throw new UnsupportedOperationException("Backend does not support MenuType factories");
        }
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
    private final Map<String, Entry> entries = new it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<>();
    /**
     * Native block identity to its associated block entity factory.
     */
    private final Map<Block, Entry> byBlock = new Reference2ObjectOpenHashMap<>();
    /**
     * Item identity to its ordered creative presentation.
     */
    private final Map<Item, String> itemTabs = new Reference2ObjectOpenHashMap<>();
    /**
     * Native tabs, including existing referenced vanilla tabs.
     */
    private final Map<String, CreativeModeTab> tabs = new Object2ObjectOpenHashMap<>();
    /**
     * Customization objects for owned tabs, absent for default tabs.
     */
    private final Map<String, ContentTab> tabConfigurations = new Object2ObjectOpenHashMap<>();
    /**
     * Native tabs created by this registration, as opposed to append-only references.
     */
    private final java.util.Set<String> ownedTabs = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>();
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
        var tabIds = new ObjectAVLTreeSet<>(plan.entries.keySet());
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
            if (entry.worldEntityConstructor != null) backend.entity(id, () -> {
                NativeContent.requireVacant(ContentKind.ENTITY, id);
                entry.worldEntityType = NativeContent.entityType(id, entry::createWorldEntity, entry.entitySettings);
                return entry.worldEntityType;
            });
            if (entry.menuConstructor != null) backend.menu(id, () -> {
                NativeContent.requireVacant(ContentKind.MENU, id);
                entry.menuType = backend.createMenuType(entry::createMenu);
                return entry.menuType;
            });
        }
    }

    /**
     * Supplies validated living-entity attributes during the loader's attribute registration phase.
     *
     * @param output native attribute registration operation
     */
    @SuppressWarnings("unchecked")
    public void appendEntityAttributes(BiConsumer<EntityType<? extends LivingEntity>, AttributeSupplier> output) {
        for (var entry : entries.values()) {
            if (entry.attributeFactory == null) {
                continue;
            }
            try {
                var builder = (AttributeSupplier.Builder) entry.attributeFactory.invokeExact();
                output.accept((EntityType<? extends LivingEntity>) entityType(entry.group.id), builder.build());
            } catch (Throwable failure) {
                throw ContentConstructors.failure(entry.group.id, failure);
            }
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
     * @param id registered world entity id
     * @return bound native world entity type
     */
    public EntityType<?> entityType(String id) {
        return bound(entry(id).worldEntityType, "EntityType", id);
    }
    /**
     * @param id registered container menu id
     * @return bound native menu type
     */
    public MenuType<?> menuType(String id) {
        return bound(entry(id).menuType, "MenuType", id);
    }
    /**
     * Resolves the declared menu class without constructing it or loading client classes.
     *
     * @param id screen's matching menu id
     * @return concrete menu implementation
     */
    public Class<? extends AbstractContainerMenu> menuClass(String id) {
        var declaration = entry(id).group.get(ContentKind.MENU);
        if (declaration == null) {
            throw new IllegalArgumentException("No annotated menu for screen id " + id);
        }
        return declaration.type().asSubclass(AbstractContainerMenu.class);
    }
    /**
     * Resolves the declared world entity class for client renderer validation.
     *
     * @param id renderer's matching entity id
     * @return concrete world entity class
     */
    public Class<? extends Entity> entityClass(String id) {
        var declaration = entry(id).group.get(ContentKind.ENTITY);
        if (declaration == null) {
            throw new IllegalArgumentException("No annotated entity for renderer id " + id);
        }
        return declaration.type().asSubclass(Entity.class);
    }
    /**
     * Constructs a menu for a server MenuProvider or a client open-menu packet.
     *
     * @param id registered menu id
     * @param windowId native container synchronization id
     * @param inventory player's inventory
     * @return newly constructed matching menu
     */
    public AbstractContainerMenu newMenu(String id, int windowId, Inventory inventory) {
        return entry(id).createMenu(windowId, Objects.requireNonNull(inventory, "inventory"));
    }
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
         * Cached EntityType/Level world entity constructor, or null.
         */
        private final MethodHandle worldEntityConstructor;
        /**
         * Cached MenuType/window id/inventory constructor, or null.
         */
        private final MethodHandle menuConstructor;
        /**
         * Conventional living-entity attributes builder, or null for nonliving content.
         */
        private final MethodHandle attributeFactory;
        /**
         * Validated optional native world entity configuration.
         */
        private final EntitySettings entitySettings;
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
         * Bound world entity type after the native registry event.
         */
        private EntityType<?> worldEntityType;
        /**
         * Bound menu type used by both server and client factories.
         */
        private MenuType<?> menuType;
        /**
         * @param group validated metadata
         */
        private Entry(ContentPlan.Group group) {
            this.group = group;
            blockConstructor = ContentConstructors.resolve(group, ContentKind.BLOCK);
            itemConstructor = ContentConstructors.resolve(group, ContentKind.ITEM);
            entityConstructor = ContentConstructors.resolve(group, ContentKind.BLOCK_ENTITY);
            tabConstructor = ContentConstructors.resolve(group, ContentKind.TAB);
            worldEntityConstructor = ContentConstructors.resolve(group, ContentKind.ENTITY);
            menuConstructor = ContentConstructors.resolve(group, ContentKind.MENU);
            var worldEntity = group.get(ContentKind.ENTITY);
            attributeFactory = worldEntity == null ? null : ContentConstructors.attributes(worldEntity.type());
            entitySettings = worldEntity == null ? null : worldEntity.type().getDeclaredAnnotation(EntitySettings.class);
            NativeContent.validateEntitySettings(entitySettings);
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
        /**
         * Invokes the cached world entity constructor when Minecraft spawns or loads an instance.
         *
         * @param type exact registered native type
         * @param level world receiving the instance
         * @return newly created world entity
         */
        private Entity createWorldEntity(EntityType<Entity> type, Level level) {
            try {
                return (Entity) worldEntityConstructor.invokeExact((EntityType) type, level);
            } catch (Throwable failure) {
                throw ContentConstructors.failure(group.id, failure);
            }
        }
        /**
         * Invokes either supported menu constructor through the same injected factory shape.
         *
         * @param windowId native synchronization id
         * @param inventory player's inventory
         * @return newly constructed menu
         */
        private AbstractContainerMenu createMenu(int windowId, Inventory inventory) {
            if (menuType == null) {
                throw new IllegalStateException("Menu type is not registered: " + group.id);
            }
            try {
                return (AbstractContainerMenu) menuConstructor.invokeExact((MenuType) menuType, windowId, inventory);
            } catch (Throwable failure) {
                throw ContentConstructors.failure(group.id, failure);
            }
        }
    }
}
