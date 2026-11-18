package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import dev.sixik.sdmal.core.content.client.ClientContentRegistration;
import dev.sixik.sdmal.core.content.client.ClientContentRuntime;
import dev.sixik.sdmal.api.content.client.RegisteredScreens;
import dev.sixik.sdmal.example.content.AnnotatedMenu;
import dev.sixik.sdmal.example.content.client.AnnotatedMenuScreen;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.Map;

import static dev.sixik.sdmal.core.content.EntityMenuRegistrationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Checks client factory validation, inherited generics, lazy construction, and native argument flow.
 */
public class ClientContentRegistrationTest {
    /**
     * Temporary constructor-only native registry scope.
     */
    private ContentRegistryScope scope;
    /**
     * Headless client singleton used by modern native Screen constructors without starting graphics.
     */
    private MockedStatic<Minecraft> clientInstance;
    /**
     * Headless client receiving queued screen display operations.
     */
    private Minecraft minecraft;

    /**
     * Initializes Minecraft's non-graphical bootstrap dependencies.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * @throws ReflectiveOperationException if constructor-only fixture internals change
     */
    @BeforeEach
    void openScope() throws ReflectiveOperationException {
        scope = new ContentRegistryScope();
        minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        clientInstance = mockStatic(Minecraft.class);
        clientInstance.when(Minecraft::getInstance).thenReturn(minecraft);
        TestRenderer.constructions = 0;
    }

    /**
     * @throws IllegalAccessException if restoring native fixture state fails
     */
    @AfterEach
    void closeScope() throws IllegalAccessException {
        try {
            if (clientInstance != null) {
                clientInstance.close();
            }
        } finally {
            scope.close();
        }
    }

    /**
     * Exact menu, inventory, and server title reach the indexed concrete screen constructor.
     */
    @Test
    void screens_matchingId_buildsConcreteScreenWithNativeArguments() {
        var common = registration(AnnotatedMenu.class);
        build(common);
        var client = client(common, AnnotatedMenuScreen.class);
        Map<MenuType<?>, ClientContentRegistration.ScreenFactory> factories = new Object2ObjectOpenHashMap<>();
        client.enlistScreens(factories::put);
        assertEquals(1, factories.size());
        var menu = mock(AnnotatedMenu.class);
        var inventory = mock(Inventory.class);
        var title = Component.literal("Server title");
        var screen = factories.get(common.menuType("sdmal:annotated_menu")).create(menu, inventory, title);
        assertInstanceOf(AnnotatedMenuScreen.class, screen);
        assertSame(menu, screen.getMenu());
        assertSame(title, screen.getTitle());
        assertThrows(IllegalStateException.class, () -> client.enlistScreens(factories::put));
    }

    /**
     * A screen constructor accepting a compatible menu superclass remains useful for vanilla reuse.
     */
    @Test
    void screens_compatibleSuperclassConstructor_acceptsRegisteredSubclass() {
        var common = registration(AnnotatedMenu.class);
        build(common);
        var client = client(common, BroadScreen.class);
        client.enlistScreens((type, factory) -> assertInstanceOf(BroadScreen.class,
                factory.create(mock(AnnotatedMenu.class), mock(Inventory.class), Component.empty())));
    }

    /**
     * No renderer instance is allocated until Minecraft supplies its renderer context.
     */
    @Test
    void renderers_inheritedGenericFactory_constructsOnlyWhenContextArrives() {
        var common = registration(TestEntity.class);
        build(common);
        var client = client(common, TestRenderer.class);
        Map<EntityType<?>, EntityRendererProvider<?>> factories = new Object2ObjectOpenHashMap<>();
        client.enlistRenderers(factories::put);
        assertEquals(0, TestRenderer.constructions);
        var context = mock(EntityRendererProvider.Context.class, RETURNS_DEEP_STUBS);
        var renderer = factories.get(common.entityType("content_extended:entity")).create(context);
        assertInstanceOf(TestRenderer.class, renderer);
        assertEquals(1, TestRenderer.constructions);
        assertThrows(IllegalStateException.class, () -> client.enlistRenderers(factories::put));
    }

    /**
     * Orphan screen/renderer ids fail before any native binding is published.
     */
    @Test
    void client_missingCommonCounterpart_rejects() {
        var empty = registration();
        assertThrows(IllegalStateException.class, () -> client(empty, AnnotatedMenuScreen.class));
        assertThrows(IllegalStateException.class, () -> client(empty, TestRenderer.class));
    }

    /**
     * Type-safe renderer/entity and screen/menu relationships are checked despite erased factories.
     */
    @Test
    void client_mismatchedGenericTypes_rejects() {
        var common = registration(TestEntity.class, WrongMenu.class);
        assertThrows(IllegalArgumentException.class, () -> client(common, WrongRenderer.class));
        assertThrows(IllegalArgumentException.class, () -> client(common, AnnotatedMenuScreen.class));
    }

    /**
     * Duplicate ids and unsupported client constructors fail when the plan is prepared.
     */
    @Test
    void client_duplicateAndInvalidFactories_rejects() {
        var common = registration(TestEntity.class, AnnotatedMenu.class);
        assertThrows(IllegalArgumentException.class, () -> client(common, TestRenderer.class, DuplicateRenderer.class));
        assertThrows(IllegalArgumentException.class, () -> client(common, InvalidRenderer.class));
        assertThrows(IllegalArgumentException.class, () -> client(common, String.class));
    }

    /**
     * The common index cannot accidentally accept a client screen class.
     */
    @Test
    void commonPlan_screenClass_rejectsClientOnlyKind() {
        assertThrows(IllegalArgumentException.class, () -> registration(AnnotatedMenuScreen.class));
    }

    /**
     * Plain screens have client-only factories without a common menu or native registry entry.
     */
    @Test
    void standaloneScreens_titleAndNoargs_createWithoutCommonMenu() {
        var client = client(registration(), PlainScreen.class, NoargScreen.class);
        var title = Component.literal("Standalone title");
        var screen = client.newScreen("content_extended:plain_screen", title);
        assertInstanceOf(PlainScreen.class, screen);
        assertSame(title, screen.getTitle());
        assertEquals("content_extended:plain_screen", client.id(PlainScreen.class));
        assertEquals("Own title", client.newScreen("content_extended:noarg_screen", title).getTitle().getString());
        assertThrows(IllegalArgumentException.class, () -> client.newScreen("missing:screen", title));
        assertThrows(IllegalArgumentException.class, () -> client.id(String.class));
    }

    /**
     * A standalone screen cannot shadow an automatically bound container screen with the same id.
     */
    @Test
    void standaloneScreen_duplicateMenuScreenId_rejectsBothDiscoveryOrders() {
        var common = registration(AnnotatedMenu.class);
        assertThrows(IllegalArgumentException.class, () -> client(common, AnnotatedMenuScreen.class, DuplicatePlainScreen.class));
        assertThrows(IllegalArgumentException.class, () -> client(common, DuplicatePlainScreen.class, AnnotatedMenuScreen.class));
    }

    /**
     * Standalone screen construction is rejected off-thread before touching the registry.
     */
    @Test
    void registeredScreens_createOffClientThread_rejects() {
        when(minecraft.isSameThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> RegisteredScreens.create(PlainScreen.class, Component.empty()));
    }

    /**
     * Public standalone opening defers construction and display to the client executor.
     */
    @Test
    void registeredScreens_open_queuesCreationAndNativeDisplay() {
        var registration = client(registration(), PlainScreen.class);
        var title = Component.literal("Opened title");
        when(minecraft.isSameThread()).thenReturn(true);
        try (var runtime = mockStatic(ClientContentRuntime.class)) {
            runtime.when(ClientContentRuntime::registration).thenReturn(registration);
            RegisteredScreens.open(PlainScreen.class, title);
            var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(minecraft).execute(task.capture());
            task.getValue().run();
            var screen = org.mockito.ArgumentCaptor.forClass(Screen.class);
            //? if >=26.3 {
            /*verify(minecraft).setScreenAndShow(screen.capture());
            *///?} else {
            verify(minecraft).setScreen(screen.capture());
            //?}
            assertInstanceOf(PlainScreen.class, screen.getValue());
            assertSame(title, screen.getValue().getTitle());
        }
    }

    /**
     * @param common prepared common declaration plan
     * @param types indexed client classes
     * @return validated client factory plan
     */
    private static ClientContentRegistration client(ContentRegistration common, Class<?>... types) {
        return new ClientContentRegistration(common, Arrays.asList(types));
    }

    /**
     * Native superclass screen constructor compatible with the registered concrete menu subclass.
     */
    @RegisterContent(id = "sdmal:annotated_menu")
    public static class BroadScreen extends ContainerScreen {
        /**
         * @param menu compatible chest-style menu
         * @param inventory player inventory
         * @param title server title
         */
        public BroadScreen(ChestMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    }

    /**
     * Generic renderer intermediary exercising inherited type-variable substitution.
     *
     * @param <T> rendered entity type
     */
    public static class BaseRenderer<T extends Entity> extends NoopRenderer<T> {
        /**
         * @param context native renderer context
         */
        public BaseRenderer(EntityRendererProvider.Context context) { super(context); }
    }

    /**
     * Concrete indexed renderer, constructed only by the provider supplied to Minecraft.
     */
    @RegisterContent(id = "content_extended:entity")
    public static class TestRenderer extends BaseRenderer<TestEntity> {
        /**
         * Number of actual renderer instances allocated in this test.
         */
        static int constructions;
        /**
         * @param context native renderer context
         */
        public TestRenderer(EntityRendererProvider.Context context) { super(context); constructions++; }
    }

    /**
     * Duplicate renderer id fixture.
     */
    @RegisterContent(id = "content_extended:entity")
    public static class DuplicateRenderer extends TestRenderer {
        /**
         * @param context native renderer context
         */
        public DuplicateRenderer(EntityRendererProvider.Context context) { super(context); }
    }

    /**
     * Renderer accepts a different entity family.
     */
    @RegisterContent(id = "content_extended:entity")
    public static class WrongRenderer extends NoopRenderer<Player> {
        /**
         * @param context native renderer context
         */
        public WrongRenderer(EntityRendererProvider.Context context) { super(context); }
    }

    /**
     * Unsupported noarg renderer constructor fixture.
     */
    @RegisterContent(id = "content_extended:entity")
    public static class InvalidRenderer extends BaseRenderer<TestEntity> {
        /**
         * Deliberately unsupported noarg constructor.
         */
        public InvalidRenderer() { super(null); }
    }

    /**
     * Menu id matches the screen, but its implementation is incompatible with ChestMenu.
     */
    @RegisterContent(id = "sdmal:annotated_menu")
    public static class WrongMenu extends TestMenu {
        /**
         * @param type menu type
         * @param id window id
         * @param inventory player inventory
         */
        public WrongMenu(MenuType<?> type, int id, Inventory inventory) { super(type, id, inventory); }
    }

    /**
     * Standalone title-injected screen fixture.
     */
    @RegisterContent(id = "content_extended:plain_screen")
    public static class PlainScreen extends Screen {
        /**
         * @param title supplied title
         */
        public PlainScreen(Component title) { super(title); }
    }

    /**
     * Standalone noarg screen fixture.
     */
    @RegisterContent(id = "content_extended:noarg_screen")
    public static class NoargScreen extends Screen {
        /**
         * Supplies its own title.
         */
        public NoargScreen() { super(Component.literal("Own title")); }
    }

    /**
     * Standalone screen colliding with a container screen.
     */
    @RegisterContent(id = "sdmal:annotated_menu")
    public static class DuplicatePlainScreen extends Screen {
        /**
         * @param title supplied title
         */
        public DuplicatePlainScreen(Component title) { super(title); }
    }
}
