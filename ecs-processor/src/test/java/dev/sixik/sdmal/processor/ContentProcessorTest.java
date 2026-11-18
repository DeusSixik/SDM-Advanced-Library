package dev.sixik.sdmal.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compiles independent consumer sources to validate constructors, categories, ids and incremental
 * indices.
 */
class ContentProcessorTest {
    /**
     * Temporary consumer project.
     */
    @TempDir Path directory;

    /**
     * Runtime annotation import namespace.
     */
    private static final String API = "dev.sixik.sdmal.api.content";

    /**
     * Diagnostics from the most recent compiler invocation.
     */
    private DiagnosticCollector<JavaFileObject> diagnostics;

    /**
     * Creates a minimal semantic Minecraft hierarchy; the processor has no Minecraft runtime
     * dependency.
     *
     * @throws IOException if fixtures cannot be written
     */
    @BeforeEach
    void fixtures() throws IOException {
        source(
                API + ".annotations.RegisterContent",
                "package "
                        + API
                        + ".annotations;"
                        + " @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
                        + " @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)"
                        + " public @interface RegisterContent { String id(); String tab() default"
                        + " \"\"; int ordinal() default 0; }");
        source(API + ".ContentTab", "package " + API + "; public interface ContentTab {}");
        source(
                "net.minecraft.world.item.Item",
                "package net.minecraft.world.item; public class Item { public static class"
                    + " Properties {} public Item(Properties p) {} }");
        source(
                "net.minecraft.world.level.block.state.BlockBehaviour",
                "package net.minecraft.world.level.block.state; public class BlockBehaviour {"
                    + " public static class Properties {} }");
        source(
                "net.minecraft.world.level.block.Block",
                "package net.minecraft.world.level.block; public class Block { public"
                    + " Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties p) {}"
                    + " }");
        source(
                "net.minecraft.world.item.BlockItem",
                "package net.minecraft.world.item; public class BlockItem extends Item { public"
                    + " BlockItem(net.minecraft.world.level.block.Block b, Properties p) {"
                    + " super(p); } }");
        source(
                "net.minecraft.core.BlockPos",
                "package net.minecraft.core; public class BlockPos {}");
        source(
                "net.minecraft.world.level.block.state.BlockState",
                "package net.minecraft.world.level.block.state; public class BlockState {}");
        source(
                "net.minecraft.world.level.block.entity.BlockEntityType",
                "package net.minecraft.world.level.block.entity; public class BlockEntityType<T>"
                    + " {}");
        source(
                "net.minecraft.world.level.block.entity.BlockEntity",
                "package net.minecraft.world.level.block.entity; public class BlockEntity { public"
                    + " BlockEntity(BlockEntityType<?> t, net.minecraft.core.BlockPos p,"
                    + " net.minecraft.world.level.block.state.BlockState s) {} }");
        source("net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level {}");
        source("net.minecraft.world.entity.EntityType", "package net.minecraft.world.entity; public class EntityType<T> {}");
        source("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity { public Entity(EntityType<?> t, net.minecraft.world.level.Level l) {} }");
        source("net.minecraft.world.entity.LivingEntity", "package net.minecraft.world.entity; public class LivingEntity extends Entity { public LivingEntity(EntityType<?> t, net.minecraft.world.level.Level l) { super(t,l); } }");
        source("net.minecraft.world.entity.ai.attributes.AttributeSupplier", "package net.minecraft.world.entity.ai.attributes; public class AttributeSupplier { public static class Builder {} }");
        source("net.minecraft.world.entity.player.Inventory", "package net.minecraft.world.entity.player; public class Inventory {}");
        source("net.minecraft.world.inventory.MenuType", "package net.minecraft.world.inventory; public class MenuType<T> {}");
        source("net.minecraft.world.inventory.AbstractContainerMenu", "package net.minecraft.world.inventory; public class AbstractContainerMenu { public AbstractContainerMenu(MenuType<?> t, int id) {} }");
        source("net.minecraft.network.chat.Component", "package net.minecraft.network.chat; public class Component {}");
        source("net.minecraft.client.gui.screens.Screen", "package net.minecraft.client.gui.screens; public class Screen { public Screen(net.minecraft.network.chat.Component title) {} }");
        source("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen", "package net.minecraft.client.gui.screens.inventory; public class AbstractContainerScreen<T extends net.minecraft.world.inventory.AbstractContainerMenu> { public AbstractContainerScreen(T m, net.minecraft.world.entity.player.Inventory i, net.minecraft.network.chat.Component c) {} }");
        source("net.minecraft.client.renderer.entity.EntityRendererProvider", "package net.minecraft.client.renderer.entity; public interface EntityRendererProvider { public class Context {} }");
        source("net.minecraft.client.renderer.entity.EntityRenderer", "package net.minecraft.client.renderer.entity; public class EntityRenderer<T extends net.minecraft.world.entity.Entity> { public EntityRenderer(EntityRendererProvider.Context c) {} }");
    }

    /**
     * Menu/entity declarations remain common while their screen/renderer classes are client-only.
     *
     * @throws IOException if fixture source/index IO fails
     */
    @Test
    void compile_entityMenuScreenAndRenderer_separatesPhysicalSides() throws IOException {
        source("consumer.WorldEntity", unit("consumer:entity", "", "public class WorldEntity extends Entity { public WorldEntity(EntityType<?> t, Level l) { super(t,l); } }"));
        source("consumer.Menu", unit("consumer:menu", "", "public class Menu extends AbstractContainerMenu { public Menu(MenuType<?> t, int id, Inventory i) { super(t,id); } }"));
        source("consumer.Screen", unit("consumer:menu", "", "public class Screen extends AbstractContainerScreen<Menu> { public Screen(Menu m, Inventory i, Component c) { super(m,i,c); } }"));
        source("consumer.Renderer", unit("consumer:entity", "", "public class Renderer extends EntityRenderer<WorldEntity> { public Renderer(EntityRendererProvider.Context c) { super(c); } }"));
        assertTrue(compile(), messages());
        assertEquals("consumer.Menu\nconsumer.WorldEntity\n", Files.readString(index()));
        assertEquals("consumer.Renderer\nconsumer.Screen\n", Files.readString(clientIndex()));
    }

    /**
     * A screen may inherit its resolved menu parameter through a generic intermediate base.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_inheritedGenericScreen_resolvesConcreteConstructor() throws IOException {
        source("consumer.Menu", imports() + "public class Menu extends AbstractContainerMenu { public Menu(int id, Inventory i) { super(null,id); } }");
        source("consumer.BaseScreen", imports() + "public class BaseScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> { public BaseScreen(T m, Inventory i, Component c) { super(m,i,c); } }");
        source("consumer.Screen", unit("consumer:menu", "", "public class Screen extends BaseScreen<Menu> { public Screen(Menu m, Inventory i, Component c) { super(m,i,c); } }"));
        assertTrue(compile(), messages());
        assertEquals("consumer.Screen\n", Files.readString(clientIndex()));
        assertEquals("", Files.readString(index()));
    }

    /**
     * A conventional menu constructor can obtain its own type instead of receiving an injected one.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_menuWithoutInjectedType_acceptsInventoryConstructor() throws IOException {
        source("consumer.Menu", unit("consumer:menu", "", "public class Menu extends AbstractContainerMenu { public Menu(int id, Inventory i) { super(null,id); } }"));
        assertTrue(compile(), messages());
    }

    /**
     * Living content requires the native public static builder contract.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_livingEntityWithoutAttributes_rejects() throws IOException {
        source("consumer.Living", unit("consumer:living", "", "public class Living extends LivingEntity { public Living(EntityType<?> t, Level l) { super(t,l); } }"));
        assertFalse(compile());
        assertTrue(messages().contains("createAttributes"), messages());
    }

    /**
     * Attributes inherited from an application's entity base remain discoverable.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_livingEntityInheritedAttributes_accepts() throws IOException {
        source("consumer.LivingBase", imports() + "public class LivingBase extends LivingEntity { public LivingBase(EntityType<?> t, Level l) { super(t,l); } public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() { return null; } }");
        source("consumer.Living", unit("consumer:living", "", "public class Living extends LivingBase { public Living(EntityType<?> t, Level l) { super(t,l); } }"));
        assertTrue(compile(), messages());
    }

    /**
     * Invalid constructor shapes are rejected before loader registry events.
     *
     * @param declaration source class
     * @throws IOException if fixture IO fails
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "public class Bad extends Entity { public Bad(Level l) { super(null,l); } }",
            "public class Bad extends AbstractContainerMenu { public Bad(int id) { super(null,id); } }",
            "public class Bad extends AbstractContainerScreen<AbstractContainerMenu> { public Bad(AbstractContainerMenu m) { super(m,null,null); } }",
            "public class Bad extends EntityRenderer<Entity> { public Bad() { super(null); } }"
    })
    void compile_newKindsInvalidConstructors_rejects(String declaration) throws IOException {
        source("consumer.Bad", unit("consumer:bad", "", declaration));
        assertFalse(compile());
        assertTrue(messages().contains("Unsupported public content constructor"), messages());
    }

    /**
     * Removing the last client annotation clears its separate output resource.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_lastClientAnnotationRemoved_clearsClientIndex() throws IOException {
        source("consumer.Renderer", unit("consumer:entity", "", "public class Renderer extends EntityRenderer<Entity> { public Renderer(EntityRendererProvider.Context c) { super(c); } }"));
        assertTrue(compile(), messages());
        source("consumer.Renderer", "package consumer; public class Renderer {}");
        assertTrue(compile(), messages());
        assertEquals("", Files.readString(clientIndex()));
    }

    /**
     * Standalone UI screens are indexed only on the physical client.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_standaloneScreensWithTitleAndNoargs_writesClientFactoriesOnly() throws IOException {
        source("consumer.Overlay", unit("consumer:overlay", "", "public class Overlay extends net.minecraft.client.gui.screens.Screen { public Overlay(Component title) { super(title); } }"));
        source("consumer.Settings", unit("consumer:settings", "", "public class Settings extends net.minecraft.client.gui.screens.Screen { public Settings() { super(null); } }"));
        assertTrue(compile(), messages());
        assertEquals("", Files.readString(index()));
        assertEquals("consumer.Overlay\nconsumer.Settings\n", Files.readString(clientIndex()));
    }

    /**
     * Client declarations are not inventory items and cannot request creative membership.
     *
     * @throws IOException if fixture IO fails
     */
    @Test
    void compile_clientScreenWithTab_rejects() throws IOException {
        source("consumer.Overlay", unit("consumer:overlay", "consumer:tab", "public class Overlay extends net.minecraft.client.gui.screens.Screen { public Overlay(Component title) { super(title); } }"));
        assertFalse(compile());
        assertTrue(messages().contains("cannot belong"), messages());
    }

    /**
     * Accepts typed constructors and shared ids across distinct native registries.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_allKindsAndSharedIds_writesDeterministicResourceIndex() throws IOException {
        source(
                "consumer.Token",
                unit(
                        "consumer:token",
                        "consumer:tab",
                        "public class Token extends Item { public Token(Item.Properties p) {"
                            + " super(p); } }"));
        source(
                "consumer.Machine",
                unit(
                        "consumer:machine",
                        "",
                        "public class Machine extends Block { public"
                            + " Machine(BlockBehaviour.Properties p) { super(p); } }"));
        source(
                "consumer.MachineEntity",
                unit(
                        "consumer:machine",
                        "consumer:tab",
                        "public class MachineEntity extends BlockEntity { public"
                            + " MachineEntity(BlockEntityType<?> t, BlockPos p, BlockState s) {"
                            + " super(t,p,s); } }"));
        source(
                "consumer.CustomItem",
                unit(
                        "consumer:machine",
                        "consumer:tab",
                        "public class CustomItem extends BlockItem { public CustomItem(Block b,"
                            + " Item.Properties p) { super(b,p); } }"));
        source(
                "consumer.Tab",
                unit("consumer:tab", "", "public class Tab implements ContentTab {}"));
        source(
                "consumer.Outer",
                imports()
                        + "public class Outer { @RegisterContent(id=\"consumer:nested\") public"
                        + " static class Nested extends Item { public Nested(Item.Properties p) {"
                        + " super(p); } } }");
        assertTrue(compile(), messages());
        assertEquals(
                "consumer.CustomItem\n"
                    + "consumer.Machine\n"
                    + "consumer.MachineEntity\n"
                    + "consumer.Outer$Nested\n"
                    + "consumer.Tab\n"
                    + "consumer.Token\n",
                Files.readString(index()));
        try (var paths = Files.walk(directory.resolve("classes"))) {
            assertTrue(paths.noneMatch(path -> path.toString().endsWith(".java")));
        }
    }

    /**
     * Reports unsupported user declarations at compilation rather than halfway through registry
     * events.
     *
     * @param declaration annotated declaration
     * @param expected diagnostic fragment
     * @throws IOException if fixtures cannot be written
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "public class Bad {}|must extend Item",
                "public abstract class Bad extends Item { public Bad(Item.Properties p) { super(p);"
                    + " } }|concrete class",
                "class Bad extends Item { public Bad(Item.Properties p) { super(p); } }|must be"
                    + " public",
                "public class Bad<T> extends Item { public Bad(Item.Properties p) { super(p); }"
                    + " }|parameters must be concrete",
                "public class Bad extends Item { public Bad() { super(new Item.Properties()); }"
                    + " }|Unsupported public content constructor",
                "public class Bad extends Item { private Bad(Item.Properties p) { super(p); }"
                    + " }|Unsupported public content constructor",
                "public class Bad extends Block { public Bad() { super(new"
                    + " BlockBehaviour.Properties()); } }|Unsupported public content constructor",
                "public class Bad implements ContentTab { private Bad() {} }|Unsupported public"
                    + " content constructor"
            })
    void compile_invalidDeclaration_rejectsWithDiagnostic(String declaration, String expected)
            throws IOException {
        source("consumer.Bad", unit("consumer:bad", "", declaration));
        assertFalse(compile());
        assertTrue(messages().contains(expected), messages());
        assertFalse(Files.exists(index()));
    }

    /**
     * Conventional two-argument native block entity factories remain supported.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_twoArgumentEntityConstructor_accepts() throws IOException {
        source(
                "consumer.Entity",
                unit(
                        "consumer:entity",
                        "",
                        "public class Entity extends BlockEntity { public Entity(BlockPos p,"
                            + " BlockState s) { super(null,p,s); } }"));
        assertTrue(compile(), messages());
    }

    /**
     * Duplicate ids in the same native registry are rejected even for distinct classes.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_duplicateSameRegistryId_rejects() throws IOException {
        source(
                "consumer.One",
                unit(
                        "consumer:same",
                        "",
                        "public class One extends Item { public One(Item.Properties p) { super(p);"
                            + " } }"));
        source(
                "consumer.Two",
                unit(
                        "consumer:same",
                        "",
                        "public class Two extends Item { public Two(Item.Properties p) { super(p);"
                            + " } }"));
        assertFalse(compile());
        assertTrue(messages().contains("Duplicate content registry id"), messages());
    }

    /**
     * Both registry and creative references use validated identifiers.
     *
     * @param id content id
     * @param tab tab id
     * @throws IOException if fixtures cannot be written
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"Invalid|consumer:tab", "consumer:item|BAD", "missingnamespace|consumer:tab"})
    void compile_invalidIdOrTab_rejects(String id, String tab) throws IOException {
        source(
                "consumer.One",
                unit(
                        id,
                        tab,
                        "public class One extends Item { public One(Item.Properties p) { super(p);"
                            + " } }"));
        assertFalse(compile());
        assertTrue(messages().contains("lowercase namespaced"), messages());
    }

    /**
     * A tab is a registry definition, not an item that can itself belong to another tab.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_tabWithParentTab_rejects() throws IOException {
        source(
                "consumer.Tab",
                unit(
                        "consumer:tab",
                        "consumer:parent",
                        "public class Tab implements ContentTab {}"));
        assertFalse(compile());
        assertTrue(messages().contains("cannot belong"), messages());
    }

    /**
     * Recompilation after annotation removal cannot retain stale content registrations.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_lastAnnotationRemoved_rewritesEmptyIndex() throws IOException {
        source(
                "consumer.One",
                unit(
                        "consumer:one",
                        "",
                        "public class One extends Item { public One(Item.Properties p) { super(p);"
                            + " } }"));
        assertTrue(compile(), messages());
        source("consumer.One", "package consumer; public class One {}");
        assertTrue(compile(), messages());
        assertEquals("", Files.readString(index()));
    }

    /**
     * @return fixture source import prefix
     */
    private String imports() {
        return "package consumer; import "
                + API
                + ".*; import "
                + API
                + ".annotations.*; import net.minecraft.world.item.*; import"
                + " net.minecraft.world.level.block.*; import"
                + " net.minecraft.world.level.block.entity.*; import"
                + " net.minecraft.world.level.block.state.*; import net.minecraft.core.*; "
                + "import net.minecraft.world.entity.*; import net.minecraft.world.entity.player.*;"
                + "import net.minecraft.world.inventory.*; import net.minecraft.world.level.*;"
                + "import net.minecraft.network.chat.*; import net.minecraft.client.gui.screens.inventory.*;"
                + "import net.minecraft.client.renderer.entity.*;";
    }

    /**
     * @param id content id
     * @param tab target tab
     * @param declaration class declaration
     * @return
     *     complete fixture source
     */
    private String unit(String id, String tab, String declaration) {
        return imports() + "@RegisterContent(id=\"" + id + "\",tab=\"" + tab + "\") " + declaration;
    }

    /**
     * @return resource output path
     */
    private Path index() {
        return directory.resolve("classes/META-INF/sdmal/content");
    }

    /**
     * @return physical-client-only compiler resource path
     */
    private Path clientIndex() {
        return directory.resolve("classes/META-INF/sdmal/content-client");
    }

    /**
     * @return compiler diagnostic text
     */
    private String messages() {
        return diagnostics.getDiagnostics().toString();
    }

    /**
     * @param name source binary name
     * @param content source text
     * @throws IOException if fixture
     *     output fails
     */
    private void source(String name, String content) throws IOException {
        Path file = directory.resolve("src/" + name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * @return compilation result
     * @throws IOException if compiler sources cannot be read
     */
    private boolean compile() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        diagnostics = new DiagnosticCollector<>();
        Files.createDirectories(directory.resolve("classes"));
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null);
                var files = Files.walk(directory.resolve("src"))) {
            var inputs =
                    manager.getJavaFileObjectsFromPaths(
                            files.filter(path -> path.toString().endsWith(".java"))
                                    .sorted()
                                    .toList());
            var task =
                    compiler.getTask(
                            null,
                            manager,
                            diagnostics,
                            List.of(
                                    "--release",
                                    "17",
                                    "-d",
                                    directory.resolve("classes").toString(),
                                    "-classpath",
                                    directory.resolve("empty").toString()),
                            null,
                            inputs);
            task.setProcessors(List.of(new ContentProcessor()));
            return task.call();
        }
    }
}
