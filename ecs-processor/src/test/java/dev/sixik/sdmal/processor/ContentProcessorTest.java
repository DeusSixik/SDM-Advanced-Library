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
                + " net.minecraft.world.level.block.state.*; import net.minecraft.core.*; ";
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
