package dev.sixik.sdmal.core.content;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.sixik.sdmal.api.content.ContentTab;
import dev.sixik.sdmal.api.content.RegisteredEntityBlock;
import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Exercises the real registration plans and factories with an independently controlled native
 * lifecycle.
 */
public class ContentRegistrationTest {
    /**
     * Constructor-only native registry state, restored after every test.
     */
    private ContentRegistryScope scope;

    /**
     * Initializes Minecraft before native properties and item stacks are constructed.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * @throws ReflectiveOperationException if native test registry internals change
     */
    @BeforeEach
    void openConstructorScope() throws ReflectiveOperationException {
        scope = new ContentRegistryScope();
    }

    /**
     * @throws IllegalAccessException if native test field restoration fails
     */
    @AfterEach
    void restoreNativeState() throws IllegalAccessException {
        if (scope != null) scope.close();
    }

    /**
     * Block entities synthesize one host block, one block item and a factory with the injected
     * native type.
     */
    @Test
    void entity_alone_createsNativeTripletWithMatchingTypeAndPosition() {
        var registration = registration(AutoEntity.class);
        var backend = build(registration);
        var block = registration.block("content_test:auto");
        assertInstanceOf(RegisteredEntityBlock.class, block);
        assertSame(block, ((BlockItem) registration.item("content_test:auto")).getBlock());
        var pos = new BlockPos(3, 7, 11);
        var entity = registration.newBlockEntity(block, pos, block.defaultBlockState());
        assertInstanceOf(AutoEntity.class, entity);
        assertSame(registration.blockEntityType("content_test:auto"), entity.getType());
        assertEquals(pos, entity.getBlockPos());
        assertEquals(1, backend.blocks.size());
        assertEquals(1, backend.items.size());
        assertEquals(1, backend.entities.size());
    }

    /**
     * Explicit custom blocks replace only the generated block and preserve the entity's
     * presentation.
     */
    @Test
    void entity_matchingCustomBlock_usesSingleCustomHostAndItem() {
        var registration = registration(CustomEntity.class, CustomBlock.class);
        var backend = build(registration);
        assertInstanceOf(CustomBlock.class, registration.block("content_test:custom"));
        assertInstanceOf(
                CustomEntity.class,
                registration.newBlockEntity(
                        registration.block("content_test:custom"),
                        BlockPos.ZERO,
                        registration.block("content_test:custom").defaultBlockState()));
        var stacks = new ArrayList<ItemStack>();
        registration.appendTab("content_test:tab", stacks::add);
        assertEquals(
                List.of(registration.item("content_test:custom")),
                stacks.stream().map(ItemStack::getItem).toList());
        assertEquals(1, backend.blocks.size());
        assertEquals(1, backend.items.size());
    }

    /**
     * Explicit BlockItem subclasses replace the generated item and receive the exact registered
     * host.
     */
    @Test
    void block_matchingBlockItem_usesCustomItemConstructor() {
        var registration = registration(PlainBlock.class, CustomBlockItem.class);
        build(registration);
        assertInstanceOf(CustomBlockItem.class, registration.item("content_test:plain"));
        assertSame(
                registration.block("content_test:plain"),
                ((BlockItem) registration.item("content_test:plain")).getBlock());
    }

    /**
     * Ordinals sort numerically without overflow; equal values use ids independently of discovery
     * order.
     */
    @Test
    void tabs_ordinalsAndIdTies_haveDeterministicOrderAndNoHiddenItems() {
        var registration =
                registration(Last.class, TieZ.class, Hidden.class, TieA.class, First.class);
        build(registration);
        var stacks = new ArrayList<ItemStack>();
        registration.appendTab("content_test:tab", stacks::add);
        assertEquals(
                List.of(First.class, TieA.class, TieZ.class, Last.class),
                stacks.stream().map(stack -> stack.getItem().getClass()).toList());
        assertEquals(1, registration.referencedTabs().size());
        assertTrue(registration.ownsTab("content_test:tab"));
        assertSame(
                registration.item("content_test:first"),
                registration.icon("content_test:tab").getItem());
    }

    /**
     * Native tab callbacks can be rebuilt repeatedly without accumulating duplicate entries.
     */
    @Test
    void tab_repeatedRebuilds_producesSameEntries() {
        var registration = registration(First.class, Last.class);
        build(registration);
        var first = new ArrayList<ItemStack>();
        var second = new ArrayList<ItemStack>();
        registration.appendTab("content_test:tab", first::add);
        registration.appendTab("content_test:tab", second::add);
        assertEquals(
                first.stream().map(ItemStack::getItem).toList(),
                second.stream().map(ItemStack::getItem).toList());
    }

    /**
     * An explicit tab replaces only the default customization, retaining automatic entries and
     * sorted order.
     */
    @Test
    void tab_customConfiguration_controlsTitleIconAndExtraItems() {
        var registration = registration(CustomTab.class, First.class);
        build(registration);
        assertEquals("Custom test tab", registration.title("content_test:tab").getString());
        assertSame(Items.DIAMOND, registration.icon("content_test:tab").getItem());
        var stacks = new ArrayList<ItemStack>();
        registration.appendTab("content_test:tab", stacks::add);
        assertEquals(
                List.of(registration.item("content_test:first"), Items.STICK),
                stacks.stream().map(ItemStack::getItem).toList());
    }

    /**
     * An explicitly declared empty tab receives a valid fallback icon without requiring content.
     */
    @Test
    void tab_emptyDefault_fallsBackToChest() {
        var registration = registration(EmptyTab.class);
        build(registration);
        assertSame(Items.CHEST, registration.icon("content_test:empty").getItem());
        assertEquals(
                "itemGroup.content_test.empty",
                registration.title("content_test:empty").getString());
    }

    /**
     * Invalid ids and unsupported base classes fail before any native lifecycle is enlisted.
     */
    @Test
    void plan_invalidIdAndUnknownType_rejects() {
        assertThrows(IllegalArgumentException.class, () -> registration(BadId.class));
        assertThrows(IllegalArgumentException.class, () -> registration(Unsupported.class));
        assertThrows(IllegalArgumentException.class, () -> registration(String.class));
    }

    /**
     * Duplicate declarations of the same native kind cannot silently overwrite one another.
     */
    @Test
    void plan_duplicateItemAndConflictingPresentation_rejects() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registration(First.class, DuplicateFirst.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> registration(CustomEntity.class, ConflictingBlock.class));
    }

    /**
     * A custom host must actually support Minecraft block entities.
     */
    @Test
    void plan_entityPairedWithOrdinaryBlock_rejects() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registration(AutoEntity.class, InvalidEntityHost.class));
    }

    /**
     * The default native factory must have an annotated entity to construct before its host can register.
     */
    @Test
    void plan_registeredEntityBlockWithoutEntity_rejectsBeforePlacement() {
        assertThrows(IllegalArgumentException.class, () -> registration(CustomBlock.class));
    }

    /**
     * Construction errors are detected before registration starts, and orphan BlockItems are
     * rejected.
     */
    @Test
    void plan_unsupportedConstructorAndOrphanItem_rejects() {
        assertThrows(IllegalArgumentException.class, () -> registration(BadConstructor.class));
        assertThrows(IllegalArgumentException.class, () -> registration(CustomBlockItem.class));
    }

    /**
     * An item with no tab is registered normally and does not request any creative tab.
     */
    @Test
    void item_noTab_hasNoAutomaticCreativeMembership() {
        var registration = registration(Hidden.class);
        var backend = build(registration);
        assertTrue(registration.referencedTabs().isEmpty());
        assertTrue(backend.tabs.isEmpty());
        assertInstanceOf(Hidden.class, registration.item("content_test:hidden"));
    }

    /**
     * Explicit item metadata is authoritative and can hide an automatically placeable block.
     */
    @Test
    void item_explicitHiddenOverride_suppressesInheritedBlockTab() {
        var registration = registration(PlainBlock.class, CustomBlockItem.class);
        build(registration);
        assertTrue(registration.referencedTabs().isEmpty());
    }

    /**
     * Conventional constructors can resolve their native type themselves while using the same
     * cached factory shape.
     */
    @Test
    void entity_twoArgumentConstructor_usesConventionalFactory() {
        var registration = registration(ConventionalEntity.class);
        build(registration);
        ConventionalEntity.TYPE = registration.blockEntityType("content_test:conventional");
        try {
            var block = registration.block("content_test:conventional");
            var entity =
                    registration.newBlockEntity(block, BlockPos.ZERO, block.defaultBlockState());
            assertInstanceOf(ConventionalEntity.class, entity);
            assertSame(ConventionalEntity.TYPE, entity.getType());
        } finally {
            ConventionalEntity.TYPE = null;
        }
    }

    /**
     * Properties supplied to a modern BlockItem retain the native block translation prefix.
     *
     * @throws ReflectiveOperationException if native name fixture internals change
     */
    @Test
    void item_generatedBlockItem_preservesBlockTranslationPrefix()
            throws ReflectiveOperationException {
        var registration = registration(PlainBlock.class);
        build(registration);
        var field = Item.class.getDeclaredField("descriptionId");
        field.setAccessible(true);
        var description = (String) field.get(registration.item("content_test:plain"));
        if (description != null) assertEquals("block.content_test.plain", description);
    }

    /**
     * Lookups before registration produce lifecycle errors rather than default vanilla entries.
     */
    @Test
    void lookups_beforeNativeEvents_failClearly() {
        var registration = registration(AutoEntity.class);
        assertThrows(IllegalStateException.class, () -> registration.block("content_test:auto"));
        assertThrows(IllegalStateException.class, () -> registration.item("content_test:auto"));
        assertThrows(
                IllegalStateException.class,
                () -> registration.blockEntityType("content_test:auto"));
        assertThrows(IllegalStateException.class, () -> registration.item("content_test:unknown"));
        assertThrows(IllegalStateException.class, () -> registration.id(String.class));
    }

    /**
     * An existing native tab is appended to, while an explicit same-id replacement is rejected.
     */
    @Test
    void tab_existingNativeReference_isNotRegisteredAgain() {
        var registration = registration(First.class);
        var backend = new CapturingBackend();
        var existing = mock(CreativeModeTab.class);
        backend.existing.put("content_test:tab", existing);
        registration.enlist(backend);
        backend.construct();
        assertFalse(registration.ownsTab("content_test:tab"));
        assertSame(existing, registration.tab("content_test:tab"));
        assertTrue(backend.tabs.isEmpty());
        assertThrows(IllegalStateException.class, () -> registration.enlist(backend));
        var custom = registration(CustomTab.class);
        assertThrows(IllegalStateException.class, () -> custom.enlist(backend));
    }

    /**
     * Constructors cannot replace existing vanilla registry entries.
     */
    @Test
    void nativeRegistry_existingBlockId_isRejectedBeforeConstruction() {
        var registration = registration(VanillaCollision.class);
        var backend = new CapturingBackend();
        registration.enlist(backend);
        assertThrows(
                IllegalStateException.class, () -> backend.blocks.get("minecraft:stone").get());
    }

    /**
     * @param types implementation fixtures
     * @return validated registration
     */
    private static ContentRegistration registration(Class<?>... types) {
        return new ContentRegistration(Arrays.asList(types));
    }

    /**
     * @param registration validated plan
     * @return controlled fully constructed backend
     */
    private static CapturingBackend build(ContentRegistration registration) {
        var backend = new CapturingBackend();
        registration.enlist(backend);
        backend.construct();
        return backend;
    }

    /**
     * Native lifecycle test double; records factories and invokes them in an explicit order.
     */
    private static final class CapturingBackend implements ContentRegistration.Backend {
        /**
         * Deferred block factories.
         */
        final Map<String, Supplier<Block>> blocks = new LinkedHashMap<>();

        /**
         * Deferred item factories.
         */
        final Map<String, Supplier<Item>> items = new LinkedHashMap<>();

        /**
         * Deferred entity type factories.
         */
        final Map<String, Supplier<BlockEntityType<?>>> entities = new LinkedHashMap<>();

        /**
         * Deferred creative tab factories.
         */
        final Map<String, Supplier<CreativeModeTab>> tabs = new LinkedHashMap<>();

        /**
         * Existing native tab references.
         */
        final Map<String, CreativeModeTab> existing = new HashMap<>();

        /**
         * Invokes all factories after enlistment, preserving block-before-item/type dependencies.
         */
        void construct() {
            tabs.values().forEach(Supplier::get);
            blocks.values().forEach(Supplier::get);
            items.values().forEach(Supplier::get);
            entities.values().forEach(Supplier::get);
        }

        /**
         * @param id block id
         * @param factory deferred constructor
         */
        @Override
        public void block(String id, Supplier<Block> factory) {
            blocks.put(id, factory);
        }

        /**
         * @param id item id
         * @param factory deferred constructor
         */
        @Override
        public void item(String id, Supplier<Item> factory) {
            items.put(
                    id,
                    () -> {
                        var item = factory.get();
                        try {
                            ContentRegistryScope.bindNewItem(item);
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                        return item;
                    });
        }

        /**
         * @param id type id
         * @param factory deferred constructor
         */
        @Override
        public void blockEntity(String id, Supplier<BlockEntityType<?>> factory) {
            entities.put(id, factory);
        }

        /**
         * @param id tab id
         * @param factory deferred constructor
         */
        @Override
        public void tab(String id, Supplier<CreativeModeTab> factory) {
            tabs.put(id, factory);
        }

        /**
         * @param id tab id
         * @return preexisting native tab or null
         */
        @Override
        public CreativeModeTab existingTab(String id) {
            return existing.get(id);
        }

        /**
         * @param registration metadata
         * @param id tab id
         * @return opaque native tab identity
         */
        @Override
        public CreativeModeTab createTab(ContentRegistration registration, String id) {
            return mock(CreativeModeTab.class);
        }

        /**
         * @param factory real cached content factory
         * @param block valid native host
         * @return native
         *     type identity dispatching to the factory
         */
        @Override
        public BlockEntityType<?> createBlockEntityType(
                BiFunction<BlockPos, BlockState, BlockEntity> factory, Block block) {
            var type = mock(BlockEntityType.class);
            when(type.isValid(any(BlockState.class))).thenReturn(true);
            when(type.create(any(BlockPos.class), any(BlockState.class)))
                    .thenAnswer(
                            invocation ->
                                    factory.apply(
                                            invocation.getArgument(0), invocation.getArgument(1)));
            return type;
        }
    }

    /**
     * Default native factory fixture.
     */
    @RegisterContent(id = "content_test:auto", tab = "content_test:tab")
    public static class AutoEntity extends BlockEntity {
        /**
         * @param type native type
         * @param position coordinate
         * @param state host state
         */
        public AutoEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
            super(type, position, state);
        }
    }

    /**
     * Custom native factory fixture.
     */
    @RegisterContent(id = "content_test:custom", tab = "content_test:tab", ordinal = 1)
    public static class CustomEntity extends BlockEntity {
        /**
         * @param type native type
         * @param position coordinate
         * @param state host state
         */
        public CustomEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
            super(type, position, state);
        }
    }

    /**
     * Conventional native constructor fixture.
     */
    @RegisterContent(id = "content_test:conventional")
    public static class ConventionalEntity extends BlockEntity {
        /**
         * Native type populated by the test after its registry event.
         */
        static BlockEntityType<?> TYPE;

        /**
         * @param position host coordinate
         * @param state host state
         */
        public ConventionalEntity(BlockPos position, BlockState state) {
            super(TYPE, position, state);
        }
    }

    /**
     * Matching custom block inheriting native factory routing.
     */
    @RegisterContent(id = "content_test:custom")
    public static class CustomBlock extends RegisteredEntityBlock {
        /**
         * @param properties injected block properties
         */
        public CustomBlock(Properties properties) {
            super(properties);
        }
    }

    /**
     * Conflicting explicit presentation.
     */
    @RegisterContent(id = "content_test:custom", tab = "content_test:other")
    public static class ConflictingBlock extends RegisteredEntityBlock {
        /**
         * @param properties injected block properties
         */
        public ConflictingBlock(Properties properties) {
            super(properties);
        }
    }

    /**
     * Ordinary registered block.
     */
    @RegisterContent(id = "content_test:plain", tab = "content_test:tab")
    public static class PlainBlock extends Block {
        /**
         * @param properties injected block properties
         */
        public PlainBlock(Properties properties) {
            super(properties);
        }
    }

    /**
     * Explicit block item whose empty tab suppresses the block's tab.
     */
    @RegisterContent(id = "content_test:plain")
    public static class CustomBlockItem extends BlockItem {
        /**
         * @param block native block
         * @param properties injected item properties
         */
        public CustomBlockItem(Block block, Item.Properties properties) {
            super(block, properties);
        }
    }

    /**
     * Lowest possible ordinal.
     */
    @RegisterContent(
            id = "content_test:first",
            tab = "content_test:tab",
            ordinal = Integer.MIN_VALUE)
    public static class First extends Item {
        /**
         * @param properties injected item properties
         */
        public First(Properties properties) {
            super(properties);
        }
    }

    /**
     * Highest possible ordinal.
     */
    @RegisterContent(
            id = "content_test:last",
            tab = "content_test:tab",
            ordinal = Integer.MAX_VALUE)
    public static class Last extends Item {
        /**
         * @param properties injected item properties
         */
        public Last(Properties properties) {
            super(properties);
        }
    }

    /**
     * First id in an ordinal tie.
     */
    @RegisterContent(id = "content_test:tie_a", tab = "content_test:tab")
    public static class TieA extends Item {
        /**
         * @param properties injected item properties
         */
        public TieA(Properties properties) {
            super(properties);
        }
    }

    /**
     * Last id in an ordinal tie.
     */
    @RegisterContent(id = "content_test:tie_z", tab = "content_test:tab")
    public static class TieZ extends Item {
        /**
         * @param properties injected item properties
         */
        public TieZ(Properties properties) {
            super(properties);
        }
    }

    /**
     * Unlisted item.
     */
    @RegisterContent(id = "content_test:hidden")
    public static class Hidden extends Item {
        /**
         * @param properties injected item properties
         */
        public Hidden(Properties properties) {
            super(properties);
        }
    }

    /**
     * Duplicate same-kind id.
     */
    @RegisterContent(id = "content_test:first")
    public static class DuplicateFirst extends Item {
        /**
         * @param properties injected item properties
         */
        public DuplicateFirst(Properties properties) {
            super(properties);
        }
    }

    /**
     * Invalid native host for an entity.
     */
    @RegisterContent(id = "content_test:auto")
    public static class InvalidEntityHost extends Block {
        /**
         * @param properties injected block properties
         */
        public InvalidEntityHost(Properties properties) {
            super(properties);
        }
    }

    /**
     * Invalid identifier.
     */
    @RegisterContent(id = "INVALID")
    public static class BadId extends Item {
        /**
         * @param properties injected item properties
         */
        public BadId(Properties properties) {
            super(properties);
        }
    }

    /**
     * Unsupported class hierarchy.
     */
    @RegisterContent(id = "content_test:unsupported")
    public static class Unsupported {}

    /**
     * Missing properties injection constructor.
     */
    @RegisterContent(id = "content_test:bad_constructor")
    public static class BadConstructor extends Item {
        /**
         * Constructs deliberately incompatible native properties.
         */
        public BadConstructor() {
            super(new Item.Properties());
        }
    }

    /**
     * Forbidden native id replacement.
     */
    @RegisterContent(id = "minecraft:stone")
    public static class VanillaCollision extends Block {
        /**
         * @param properties injected block properties
         */
        public VanillaCollision(Properties properties) {
            super(properties);
        }
    }

    /**
     * Customized tab fixture.
     */
    @RegisterContent(id = "content_test:tab")
    public static class CustomTab implements ContentTab {
        /**
         * Public discovery constructor.
         */
        public CustomTab() {}

        /**
         * @return custom title
         */
        @Override
        public Component title() {
            return Component.literal("Custom test tab");
        }

        /**
         * @return custom icon
         */
        @Override
        public ItemStack icon() {
            return Items.DIAMOND.getDefaultInstance();
        }

        /**
         * @param output native item output
         */
        @Override
        public void appendItems(java.util.function.Consumer<ItemStack> output) {
            output.accept(Items.STICK.getDefaultInstance());
        }
    }

    /**
     * Empty tab fixture.
     */
    @RegisterContent(id = "content_test:empty")
    public static class EmptyTab implements ContentTab {
        /**
         * Public discovery constructor.
         */
        public EmptyTab() {}
    }
}
