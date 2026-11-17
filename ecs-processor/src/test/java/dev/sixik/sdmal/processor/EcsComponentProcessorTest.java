package dev.sixik.sdmal.processor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles independent consumer sources to exercise semantic validation and service descriptor output. */
class EcsComponentProcessorTest {
    /** Temporary consumer project owned by the current test. */
    @TempDir Path directory;
    /** Minimal runtime API package used without introducing any Minecraft dependency into the processor. */
    private static final String API = "dev.sixik.sdmal.api.ecs";
    /** Relative output path consumed by Java's ServiceLoader. */
    private static final String INDEX = "META-INF/services/" + API + ".WorldObjectComponent";

    /**
     * Creates an independent API with the annotation and marker service contract.
     * @throws IOException if test fixture files cannot be created
     */
    @BeforeEach void api() throws IOException {
        source(API + ".WorldObjectComponent", "package " + API + "; public interface WorldObjectComponent {}");
        source(API + ".annotations.EcsComponent", "package " + API + ".annotations; "
            + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
            + "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE) "
            + "public @interface EcsComponent { String id(); }");
    }

    /**
     * Uses semantic annotation lookup, nested binary names and deterministic ordering without generating Java.
     * @throws IOException if test files cannot be read or compiled
     */
    @Test void compile_qualifiedAndNestedComponents_writesOnlySortedServiceDescriptor() throws IOException {
        source("consumer.Zed", component("Zed", "consumer:zed", "public class Zed implements WorldObjectComponent {}"));
        source("consumer.Alpha", "package consumer; @" + API + ".annotations.EcsComponent(id=\"consumer:alpha\") "
            + "public class Alpha implements " + API + ".WorldObjectComponent {}");
        source("consumer.Outer", "package consumer; import " + API + ".*; import " + API + ".annotations.*; public class Outer { "
            + "@EcsComponent(id=\"consumer:nested\") public static class Nested implements WorldObjectComponent {} }");
        source("consumer.Comment", "package consumer; // @EcsComponent(id=\"ignored:comment\")\npublic class Comment {}");
        assertTrue(compile(), diagnostics());
        assertEquals("consumer.Alpha\nconsumer.Outer$Nested\nconsumer.Zed\n", Files.readString(index()));
        try (var files = Files.walk(directory.resolve("classes"))) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".java")));
        }
    }

    /**
     * Reports invalid service providers at the component declaration instead of failing at game startup.
     * @param declaration component declaration following its annotation
     * @param expected diagnostic fragment identifying the invalid contract
     * @throws IOException if the consumer source cannot be written
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "public abstract class Bad implements WorldObjectComponent {}|concrete class",
        "public interface Bad extends WorldObjectComponent {}|concrete class",
        "public class Bad {}|must implement",
        "class Bad implements WorldObjectComponent {}|public component",
        "public class Bad implements WorldObjectComponent { private Bad() {} }|public no-argument constructor",
        "public class Bad implements WorldObjectComponent { public Bad(int value) {} }|public no-argument constructor"
    })
    void compile_invalidProvider_failsWithSourceDiagnostic(String declaration, String expected) throws IOException {
        source("consumer.Bad", component("Bad", "consumer:bad", declaration));
        assertFalse(compile());
        assertTrue(diagnostics().contains(expected), diagnostics());
        assertFalse(Files.exists(index()));
    }

    /**
     * Rejects non-static inner providers whose actual constructor needs an enclosing instance.
     * @throws IOException if the consumer source cannot be written
     */
    @Test void compile_innerComponent_requiresStatic() throws IOException {
        source("consumer.Outer", "package consumer; import " + API + ".*; import " + API + ".annotations.*; public class Outer { "
            + "@EcsComponent(id=\"consumer:inner\") public class Inner implements WorldObjectComponent {} }");
        assertFalse(compile());
        assertTrue(diagnostics().contains("to be static"), diagnostics());
    }

    /**
     * Rejects identifiers that cannot be used as stable namespaced archive keys.
     * @throws IOException if the consumer source cannot be written
     */
    @Test void compile_invalidIdentifier_failsBeforeWritingIndex() throws IOException {
        source("consumer.Bad", component("Bad", "UpperCase", "public class Bad implements WorldObjectComponent {}"));
        assertFalse(compile());
        assertTrue(diagnostics().contains("namespaced identifier"), diagnostics());
    }

    /**
     * Rejects duplicate ids across different classes within a consumer compilation.
     * @throws IOException if the consumer sources cannot be written
     */
    @Test void compile_duplicateIds_reportsBothClasses() throws IOException {
        source("consumer.One", component("One", "consumer:same", "public class One implements WorldObjectComponent {}"));
        source("consumer.Two", component("Two", "consumer:same", "public class Two implements WorldObjectComponent {}"));
        assertFalse(compile());
        assertTrue(diagnostics().contains("Duplicate @EcsComponent id"), diagnostics());
        assertFalse(Files.exists(index()));
    }

    /**
     * Rebuilding after removals cannot retain providers that no longer carry the annotation.
     * @throws IOException if consumer sources or the descriptor cannot be updated
     */
    @Test void compile_removingLastAnnotation_replacesPreviousIndexWithEmptyDescriptor() throws IOException {
        source("consumer.One", component("One", "consumer:one", "public class One implements WorldObjectComponent {}"));
        source("consumer.Two", component("Two", "consumer:two", "public class Two implements WorldObjectComponent {}"));
        assertTrue(compile(), diagnostics());
        source("consumer.One", "package consumer; public class One {}");
        assertTrue(compile(), diagnostics());
        assertEquals("consumer.Two\n", Files.readString(index()));
        source("consumer.Two", "package consumer; public class Two {}");
        assertTrue(compile(), diagnostics());
        assertEquals("", Files.readString(index()));
    }

    /** Compiler diagnostics from the last independent compilation. */
    private DiagnosticCollector<JavaFileObject> messages;

    /**
     * Compiles all consumer sources into the same output, allowing stale-index regression checks.
     * @return whether javac accepted the complete consumer compilation
     * @throws IOException if the compiler file manager cannot read sources
     */
    private boolean compile() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK with javac");
        messages = new DiagnosticCollector<>();
        Files.createDirectories(directory.resolve("classes"));
        try (var manager = compiler.getStandardFileManager(messages, null, null);
             var sources = Files.walk(directory.resolve("src"))) {
            var inputs = manager.getJavaFileObjectsFromPaths(sources.filter(path -> path.toString().endsWith(".java")).sorted().toList());
            var task = compiler.getTask(null, manager, messages,
                List.of("--release", "17", "-d", directory.resolve("classes").toString(),
                    "-classpath", directory.resolve("empty-classpath").toString()), null, inputs);
            task.setProcessors(List.of(new EcsComponentProcessor()));
            return task.call();
        }
    }

    /**
     * Writes a named fixture to its conventional source path.
     * @param name fully qualified type name
     * @param text complete Java source
     * @throws IOException if directories or source files cannot be written
     */
    private void source(String name, String text) throws IOException {
        Path file = directory.resolve("src/" + name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /**
     * Wraps an annotated declaration in the consumer's package and API imports.
     * @param name class name used for readable fixture call sites
     * @param id stable component id
     * @param declaration Java class declaration
     * @return complete independent consumer source
     */
    private String component(String name, String id, String declaration) {
        return "package consumer; import " + API + ".*; import " + API + ".annotations.*; @EcsComponent(id=\"" + id + "\") " + declaration;
    }

    /**
     * Returns the descriptor generated by the current consumer build.
     * @return output path, which may not yet exist
     */
    private Path index() { return directory.resolve("classes/" + INDEX); }

    /**
     * Formats compiler diagnostics for failed assertions.
     * @return diagnostic text from the last compilation
     */
    private String diagnostics() { return messages.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n")); }
}
