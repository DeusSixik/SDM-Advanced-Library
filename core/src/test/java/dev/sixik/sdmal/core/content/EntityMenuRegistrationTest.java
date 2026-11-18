package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.api.content.annotations.EntitySettings;
import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import dev.sixik.sdmal.example.content.AnnotatedMenu;
import dev.sixik.sdmal.example.content.AnnotatedToken;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Validates native entity/menu factories separately from loader-specific registry publication.
 */
public class EntityMenuRegistrationTest {
    /**
     * Constructor-only registry scope restored after each test.
     */
    private ContentRegistryScope scope;

    /**
     * Initializes Minecraft bootstrap dependencies.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * @throws ReflectiveOperationException if constructor fixture internals change
     */
    @BeforeEach
    void openScope() throws ReflectiveOperationException {
        scope = new ContentRegistryScope();
    }

    /**
     * @throws IllegalAccessException if native fixture restoration fails
     */
    @AfterEach
    void closeScope() throws IllegalAccessException {
        scope.close();
    }

    /**
     * The same registered menu type is injected into both server and native client factories.
     */
    @Test
    void menu_injectedFactory_preservesTypeWindowAndInventory() {
        var registration = registration(TestMenu.class);
        var backend = build(registration);
        var inventory = mock(Inventory.class);
        var type = registration.menuType("content_extended:menu");
        var server = (TestMenu) registration.newMenu("content_extended:menu", 17, inventory);
        var client = (TestMenu) type.create(23, inventory);
        assertSame(type, server.getType());
        assertSame(type, client.getType());
        assertEquals(17, server.containerId);
        assertEquals(23, client.containerId);
        assertSame(inventory, client.inventory);
        assertSame(inventory, server.inventory);
        assertSame(TestMenu.class, registration.menuClass("content_extended:menu"));
        assertEquals(1, backend.menus.size());
        assertTrue(registration.referencedTabs().isEmpty());
    }

    /**
     * Conventional public (int, Inventory) constructors remain supported.
     */
    @Test
    void menu_conventionalFactory_usesSameNativeContract() {
        var registration = registration(ConventionalMenu.class);
        build(registration);
        ConventionalMenu.type = registration.menuType("content_extended:conventional");
        try {
            var menu = registration.newMenu("content_extended:conventional", 5, mock(Inventory.class));
            assertSame(ConventionalMenu.type, menu.getType());
            assertEquals(5, menu.containerId);
        } finally {
            ConventionalMenu.type = null;
        }
    }

    /**
     * World entities create only EntityType; they do not synthesize blocks/items like BlockEntity.
     */
    @Test
    void entity_settingsAndAttributes_registerOnlyNativeWorldType() {
        var registration = registration(TestEntity.class);
        var backend = build(registration);
        var type = registration.entityType("content_extended:entity");
        assertEquals(0.75f, type.getWidth(), 0.0f);
        assertEquals(2.25f, type.getHeight(), 0.0f);
        assertEquals(10, type.clientTrackingRange());
        assertEquals(7, type.updateInterval());
        assertTrue(type.fireImmune());
        assertSame(TestEntity.class, registration.entityClass("content_extended:entity"));
        Map<EntityType<?>, AttributeSupplier> attributes = new Object2ObjectOpenHashMap<>();
        registration.appendEntityAttributes(attributes::put);
        assertEquals(1, attributes.size());
        assertNotNull(attributes.get(type));
        assertEquals(1, backend.entities.size());
        assertTrue(backend.menus.isEmpty());
    }

    /**
     * Identical logical ids remain independent across EntityType and MenuType registries.
     */
    @Test
    void entityAndMenu_sameId_bindIndependentNativeTypes() {
        var registration = registration(TestEntity.class, EntityIdMenu.class);
        build(registration);
        assertNotNull(registration.entityType("content_extended:entity"));
        assertNotNull(registration.menuType("content_extended:entity"));
        assertThrows(IllegalStateException.class, () -> registration.block("content_extended:entity"));
        assertThrows(IllegalStateException.class, () -> registration.item("content_extended:entity"));
    }

    /**
     * Invalid settings, duplicate declarations, and invalid constructors fail before enlistment.
     */
    @Test
    void plan_invalidSettingsAndFactories_rejectsBeforeNativeWrites() {
        for (Class<?> type : new Class<?>[] {BadDimensions.class, BadInterval.class, BadTracking.class,
                BadMenu.class, EntityWithTab.class, MenuWithTab.class}) {
            assertThrows(IllegalArgumentException.class, () -> registration(type), type.getName());
        }
        assertThrows(IllegalArgumentException.class, () -> registration(TestMenu.class, DuplicateMenu.class));
    }

    /**
     * Lookups before registry events and missing common counterparts produce explicit diagnostics.
     */
    @Test
    void lookup_unboundMenuAndEntity_reportsLifecycleFailure() {
        var registration = registration(TestMenu.class, TestEntity.class);
        assertThrows(IllegalStateException.class, () -> registration.menuType("content_extended:menu"));
        assertThrows(IllegalStateException.class, () -> registration.entityType("content_extended:entity"));
        assertThrows(IllegalStateException.class,
                () -> registration.newMenu("content_extended:menu", 1, mock(Inventory.class)));
        assertThrows(IllegalArgumentException.class, () -> registration.menuClass("content_extended:entity"));
        assertThrows(IllegalArgumentException.class, () -> registration.entityClass("content_extended:menu"));
    }

    /**
     * The playable example opens its authoritative menu through the same injected native factory.
     */
    @Test
    void exampleToken_serverUse_opensRegisteredMenuProvider() {
        var common = registration(AnnotatedMenu.class);
        build(common);
        var token = new AnnotatedToken(NativeContent.itemProperties("content_extended:token", null, false));
        var level = mock(Level.class);
        var player = mock(Player.class);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        try (var runtime = mockStatic(ContentRuntime.class)) {
            runtime.when(ContentRuntime::registration).thenReturn(common);
            token.use(level, player, InteractionHand.MAIN_HAND);
            var provider = org.mockito.ArgumentCaptor.forClass(MenuProvider.class);
            verify(player).openMenu(provider.capture());
            var menu = provider.getValue().createMenu(31, mock(Inventory.class), player);
            assertInstanceOf(AnnotatedMenu.class, menu);
            assertEquals(31, menu.containerId);
            assertSame(common.menuType("sdmal:annotated_menu"), menu.getType());
            assertEquals(45, menu.slots.size());
        }
    }

    /**
     * The client interaction consumes the token click without constructing a second local menu.
     */
    @Test
    void exampleToken_clientUse_doesNotOpenMenuLocally() {
        var token = new AnnotatedToken(NativeContent.itemProperties("content_extended:token", null, false));
        var level = mock(Level.class);
        when(level.isClientSide()).thenReturn(true);
        var player = mock(Player.class);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        token.use(level, player, InteractionHand.MAIN_HAND);
        verify(player, never()).openMenu(any(MenuProvider.class));
    }

    /**
     * @param types common declaration fixtures
     * @return fully validated common plan
     */
    static ContentRegistration registration(Class<?>... types) {
        return new ContentRegistration(Arrays.asList(types));
    }

    /**
     * @param registration validated plan
     * @return captured and constructed world entity/menu backend
     */
    static Backend build(ContentRegistration registration) {
        var backend = new Backend();
        registration.enlist(backend);
        backend.entities.values().forEach(Supplier::get);
        backend.menus.values().forEach(Supplier::get);
        return backend;
    }

    /**
     * Minimal lifecycle test backend using actual vanilla MenuType via its private factory interface.
     * Production loaders use their public access-widened/extension APIs instead of reflection.
     */
    static final class Backend implements ContentRegistration.Backend {
        /**
         * Deferred native world type factories.
         */
        final Map<String, Supplier<EntityType<?>>> entities = new Object2ObjectOpenHashMap<>();
        /**
         * Deferred native menu type factories.
         */
        final Map<String, Supplier<MenuType<?>>> menus = new Object2ObjectOpenHashMap<>();

        /**
         * {@inheritDoc}
         */
        @Override
        public void entity(String id, Supplier<EntityType<?>> factory) { entities.put(id, factory); }
        /**
         * {@inheritDoc}
         */
        @Override
        public void menu(String id, Supplier<MenuType<?>> factory) { menus.put(id, factory); }
        /**
         * {@inheritDoc}
         */
        @Override
        public void block(String id, Supplier<Block> factory) { fail("Unexpected block: " + id); }
        /**
         * {@inheritDoc}
         */
        @Override
        public void item(String id, Supplier<Item> factory) { fail("Unexpected item: " + id); }
        /**
         * {@inheritDoc}
         */
        @Override
        public void blockEntity(String id, Supplier<BlockEntityType<?>> factory) { fail("Unexpected BlockEntity: " + id); }
        /**
         * {@inheritDoc}
         */
        @Override
        public void tab(String id, Supplier<CreativeModeTab> factory) { fail("Unexpected tab: " + id); }
        /**
         * {@inheritDoc}
         */
        @Override
        public CreativeModeTab existingTab(String id) { return null; }
        /**
         * {@inheritDoc}
         */
        @Override
        public CreativeModeTab createTab(ContentRegistration registration, String id) { throw new AssertionError(id); }
        /**
         * {@inheritDoc}
         */
        @Override
        public BlockEntityType<?> createBlockEntityType(BiFunction<BlockPos, BlockState, BlockEntity> factory, Block block) {
            throw new AssertionError("Unexpected BlockEntityType factory");
        }
        /**
         * {@inheritDoc}
         */
        @Override
        public MenuType<?> createMenuType(ContentRegistration.MenuFactory factory) {
            try {
                Class<?> supplierType = Class.forName("net.minecraft.world.inventory.MenuType$MenuSupplier");
                var supplier = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {supplierType},
                        (proxy, method, arguments) -> factory.create((Integer) arguments[0], (Inventory) arguments[1]));
                var constructor = MenuType.class.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                if (constructor.getParameterCount() == 1) {
                    return (MenuType<?>) constructor.newInstance(supplier);
                }
                Object flags = Class.forName("net.minecraft.world.flag.FeatureFlags").getField("DEFAULT_FLAGS").get(null);
                return (MenuType<?>) constructor.newInstance(supplier, flags);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    /**
     * Injected menu constructor fixture.
     */
    @RegisterContent(id = "content_extended:menu")
    public static class TestMenu extends AbstractContainerMenu {
        /**
         * Player inventory supplied to the constructor.
         */
        final Inventory inventory;
        /**
         * @param type registered menu type
         * @param id window id
         * @param inventory player inventory
         */
        public TestMenu(MenuType<?> type, int id, Inventory inventory) {
            super(type, id);
            this.inventory = inventory;
        }
        /**
         * {@inheritDoc}
         */
        @Override public boolean stillValid(Player player) { return true; }
        /**
         * {@inheritDoc}
         */
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    /**
     * Conventional menu factory fixture.
     */
    @RegisterContent(id = "content_extended:conventional")
    public static class ConventionalMenu extends TestMenu {
        /**
         * Native type manually resolved by this alternative constructor.
         */
        static MenuType<?> type;
        /**
         * @param id window id
         * @param inventory player inventory
         */
        public ConventionalMenu(int id, Inventory inventory) { super(type, id, inventory); }
    }

    /**
     * Independent menu sharing a world entity id.
     */
    @RegisterContent(id = "content_extended:entity")
    public static class EntityIdMenu extends TestMenu {
        /**
         * @param type menu type
         * @param id window id
         * @param inventory player inventory
         */
        public EntityIdMenu(MenuType<?> type, int id, Inventory inventory) { super(type, id, inventory); }
    }

    /**
     * Duplicate menu declaration rejected by startup validation.
     */
    @RegisterContent(id = "content_extended:menu")
    public static class DuplicateMenu extends TestMenu {
        /**
         * @param type menu type
         * @param id window id
         * @param inventory player inventory
         */
        public DuplicateMenu(MenuType<?> type, int id, Inventory inventory) { super(type, id, inventory); }
    }

    /**
     * Unsupported menu constructor fixture.
     */
    @RegisterContent(id = "content_extended:bad_menu")
    public static class BadMenu extends TestMenu {
        /**
         * Unsupported noarg constructor.
         */
        public BadMenu() { super(null, 0, null); }
    }

    /**
     * Native living entity with explicit settings.
     */
    @RegisterContent(id = "content_extended:entity")
    @EntitySettings(width = 0.75f, height = 2.25f, trackingRange = 10, updateInterval = 7, fireImmune = true)
    public static class TestEntity extends ArmorStand {
        /**
         * @param type native type
         * @param level world
         */
        public TestEntity(EntityType<? extends ArmorStand> type, Level level) { super(type, level); }
        /**
         * @return basic living attributes
         */
        public static AttributeSupplier.Builder createAttributes() { return createLivingAttributes(); }
    }

    /**
     * Nonfinite dimensions rejected before native type construction.
     */
    @RegisterContent(id = "content_extended:bad_dimensions")
    @EntitySettings(width = Float.NaN)
    public static class BadDimensions extends TestEntity {
        /**
         * @param type native type
         * @param level world
         */
        public BadDimensions(EntityType<? extends ArmorStand> type, Level level) { super(type, level); }
    }

    /**
     * Invalid update interval fixture.
     */
    @RegisterContent(id = "content_extended:bad_interval")
    @EntitySettings(updateInterval = 0)
    public static class BadInterval extends TestEntity {
        /**
         * @param type native type
         * @param level world
         */
        public BadInterval(EntityType<? extends ArmorStand> type, Level level) { super(type, level); }
    }

    /**
     * Invalid tracking distance fixture.
     */
    @RegisterContent(id = "content_extended:bad_tracking")
    @EntitySettings(trackingRange = -1)
    public static class BadTracking extends TestEntity {
        /**
         * @param type native type
         * @param level world
         */
        public BadTracking(EntityType<? extends ArmorStand> type, Level level) { super(type, level); }
    }

    /**
     * Entity is not itself a creative-tab item.
     */
    @RegisterContent(id = "content_extended:tabbed_entity", tab = "content_extended:tab")
    public static class EntityWithTab extends TestEntity {
        /**
         * @param type native type
         * @param level world
         */
        public EntityWithTab(EntityType<? extends ArmorStand> type, Level level) { super(type, level); }
    }

    /**
     * Menu is not itself a creative-tab item.
     */
    @RegisterContent(id = "content_extended:tabbed_menu", tab = "content_extended:tab")
    public static class MenuWithTab extends TestMenu {
        /**
         * @param type native type
         * @param id window id
         * @param inventory player inventory
         */
        public MenuWithTab(MenuType<?> type, int id, Inventory inventory) { super(type, id, inventory); }
    }
}
