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
 * Compiles real consumer declarations without a Minecraft dependency.
 */
class NetworkPacketProcessorTest {
    /**
     * Temporary compilation directory.
     */
    @TempDir Path directory;

    /**
     * Minimal runtime API namespace.
     */
    private static final String API = "dev.sixik.sdmal.api.network";

    /**
     * Diagnostics from the most recent compiler run.
     */
    private DiagnosticCollector<JavaFileObject> diagnostics;

    /**
     * Creates the processor's annotation and interface fixture.
     *
     * @throws IOException if fixtures cannot be written
     */
    @BeforeEach
    void api() throws IOException {
        source(API + ".Packet", "package " + API + "; public interface Packet {}");
        source(API + ".Side", "package " + API + "; public enum Side { CLIENT, SERVER }");
        source(
                API + ".annotations.NetworkPacket",
                "package "
                        + API
                        + ".annotations;"
                        + " @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
                        + " @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)"
                        + " public @interface NetworkPacket { String id(); "
                        + API
                        + ".Side side(); }");
    }

    /**
     * Indexes records, ordinary classes and nested binary names without generated sources.
     *
     * @throws IOException if compilation fixtures fail
     */
    @Test
    void compile_recordsAndNestedTypes_writesSortedResourceOnlyIndex() throws IOException {
        source("consumer.Zed", packet("zed", "public record Zed(int number) implements Packet {}"));
        source(
                "consumer.Alpha",
                packet("alpha", "public class Alpha implements Packet { private Alpha() {} }"));
        source(
                "consumer.Outer",
                "package consumer; import "
                        + API
                        + ".*; import "
                        + API
                        + ".annotations.*; public class Outer {"
                        + " @NetworkPacket(id=\"consumer:nested\", side=Side.CLIENT) public record"
                        + " Nested(String text) implements Packet {} }");
        assertTrue(compile(), messages());
        assertEquals(
                "consumer.Alpha\nconsumer.Outer$Nested\nconsumer.Zed\n", Files.readString(index()));
        try (var paths = Files.walk(directory.resolve("classes"))) {
            assertTrue(paths.noneMatch(path -> path.toString().endsWith(".java")));
        }
    }

    /**
     * Reports incompatible class declarations at build time.
     *
     * @param declaration invalid packet declaration
     * @param expected required diagnostic fragment
     * @throws IOException if fixtures cannot be written
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "public abstract class Bad implements Packet {}|concrete class or record",
                "public interface Bad extends Packet {}|concrete class or record",
                "public class Bad {}|must implement",
                "class Bad implements Packet {}|must be public",
                "public class Bad implements Packet { public Bad(int n) {} }|no-argument"
                        + " constructor",
                "public class Bad implements Packet { final int n=1; }|Final serialized",
                "public class Bad<T> implements Packet {}|type parameters"
            })
    void compile_invalidDeclaration_failsClearly(String declaration, String expected)
            throws IOException {
        source("consumer.Bad", packet("bad", declaration));
        assertFalse(compile());
        assertTrue(messages().contains(expected), messages());
        assertFalse(Files.exists(index()));
    }

    /**
     * Mutable classes can exclude final local state through transient fields.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_transientFinalFields_permitsLocalOnlyState() throws IOException {
        source(
                "consumer.Valid",
                packet(
                        "valid",
                        "public class Valid implements Packet { transient final Object cache=new"
                                + " Object(); static final int CONSTANT=1; int value; }"));
        assertTrue(compile(), messages());
    }

    /**
     * Duplicate logical ids fail even if the receiving sides differ.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_duplicateId_rejects() throws IOException {
        source("consumer.One", packet("same", "public record One() implements Packet {}"));
        source("consumer.Two", packet("same", "public record Two() implements Packet {}"));
        assertFalse(compile());
        assertTrue(messages().contains("Duplicate packet id"), messages());
    }

    /**
     * Removing the last annotation cannot leave an outdated packet index.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_lastAnnotationRemoved_writesEmptyIndex() throws IOException {
        source("consumer.One", packet("one", "public record One() implements Packet {}"));
        assertTrue(compile(), messages());
        source("consumer.One", "package consumer; public record One() {}");
        assertTrue(compile(), messages());
        assertEquals("", Files.readString(index()));
    }

    /**
     * Invalid resource identifiers are rejected by the compiler.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_invalidId_rejects() throws IOException {
        source("consumer.One", packet("UPPER", "public record One() implements Packet {}"));
        assertFalse(compile());
        assertTrue(messages().contains("lowercase namespaced"), messages());
    }

    /**
     * Non-static nested packets cannot be instantiated from an independent frame.
     *
     * @throws IOException if fixtures cannot be written
     */
    @Test
    void compile_nonStaticNested_rejects() throws IOException {
        source(
                "consumer.Outer",
                "package consumer; import "
                        + API
                        + ".*; import "
                        + API
                        + ".annotations.*; public class Outer {"
                        + " @NetworkPacket(id=\"consumer:nested\", side=Side.SERVER) public class"
                        + " Nested implements Packet {} }");
        assertFalse(compile());
        assertTrue(messages().contains("must be static"), messages());
    }

    /**
     * @param id packet path
     * @param declaration source declaration @return complete source
     */
    private String packet(String id, String declaration) {
        return "package consumer; import "
                + API
                + ".*; import "
                + API
                + ".annotations.*; @NetworkPacket(id=\"consumer:"
                + id
                + "\", side=Side.SERVER) "
                + declaration;
    }

    /**
     * @return compiled resource path
     */
    private Path index() {
        return directory.resolve("classes/META-INF/sdmal/network-packets");
    }

    /**
     * @return diagnostic text for assertion failures
     */
    private String messages() {
        return diagnostics.getDiagnostics().toString();
    }

    /**
     * Writes a compilation unit.
     *
     * @param name qualified name
     * @param contents source code
     * @throws IOException if fixtures cannot be written
     */
    private void source(String name, String contents) throws IOException {
        Path path = directory.resolve("src/" + name.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    /**
     * Runs the actual annotation processor with a minimal independent classpath.
     *
     * @return compilation success
     * @throws IOException if the file manager cannot read sources
     */
    private boolean compile() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        diagnostics = new DiagnosticCollector<>();
        Files.createDirectories(directory.resolve("classes"));
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null);
                var paths = Files.walk(directory.resolve("src"))) {
            var sources =
                    manager.getJavaFileObjectsFromPaths(
                            paths.filter(path -> path.toString().endsWith(".java"))
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
                            sources);
            task.setProcessors(List.of(new NetworkPacketProcessor()));
            return task.call();
        }
    }
}
