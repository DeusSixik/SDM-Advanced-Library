package dev.sixik.sdmal.processor;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Compiles request declarations independently of Minecraft and validates resource index behavior. */
class AsyncRequestProcessorTest {
    /** Temporary compiler project. */
    @TempDir Path directory;
    /** API namespace used by fixtures. */
    private static final String API = "dev.sixik.sdmal.api.network";
    /** Last compiler diagnostics. */
    private DiagnosticCollector<JavaFileObject> diagnostics;
    /** Writes a minimal request API fixture before each test. */
    @BeforeEach void fixtures() throws IOException {
        source(API + ".AsyncRequest", "package " + API + "; public interface AsyncRequest<R> { void handle(Object c, Object r); }");
        source(API + ".Side", "package " + API + "; public enum Side { CLIENT, SERVER }");
        source(API + ".annotations.NetworkRequest", "package " + API + ".annotations; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE) public @interface NetworkRequest { String id(); " + API + ".Side side(); Class<?> response(); long timeoutMillis() default 30000; }");
    }
    /** Records and classes receive a deterministic binary index. */
    @Test void compile_validRecordAndClass_writesSortedIndex() throws IOException {
        source("consumer.Zed", unit("consumer:zed", "public record Zed(long value) implements AsyncRequest<String> { public void handle(Object c,Object r) {} }"));
        source("consumer.Alpha", unit("consumer:alpha", "public class Alpha implements AsyncRequest<String> { public Alpha() {} public void handle(Object c,Object r) {} }"));
        assertTrue(compile(), diagnosticsText());
        assertEquals("consumer.Alpha\nconsumer.Zed\n", Files.readString(index()));
    }
    /** Invalid base type, visibility, constructor, timeout and id fail at compile time. */
    @ParameterizedTest
    @CsvSource(delimiter='|', value={
        "public record Bad() {}|must implement",
        "class Bad implements AsyncRequest<String> { public Bad() {} public void handle(Object c,Object r) {} }|must be public",
        "public class Bad implements AsyncRequest<String> { public Bad(int value) {} public void handle(Object c,Object r) {} }|no-argument",
        "public record Bad() implements AsyncRequest<String> { public void handle(Object c,Object r) {} }|id",
        "public record Bad() implements AsyncRequest<String> { public void handle(Object c,Object r) {} }|timeout"
    })
    void compile_invalidRequest_failsWithDiagnostic(String declaration, String expected) throws IOException {
        String id = expected.equals("id") ? "UPPER" : "consumer:bad";
        String timeout = expected.equals("timeout") ? ",timeoutMillis=0" : "";
        source("consumer.Bad", imports() + "@NetworkRequest(id=\"" + id + "\",side=Side.SERVER,response=String.class" + timeout + ") " + declaration);
        assertFalse(compile()); assertTrue(diagnosticsText().contains(expected), diagnosticsText()); assertFalse(Files.exists(index()));
    }
    /** Duplicate ids are rejected independently of request Java classes. */
    @Test void compile_duplicateIds_rejects() throws IOException {
        source("consumer.One", unit("consumer:same", "public record One() implements AsyncRequest<String> { public void handle(Object c,Object r) {} }"));
        source("consumer.Two", unit("consumer:same", "public record Two() implements AsyncRequest<String> { public void handle(Object c,Object r) {} }"));
        assertFalse(compile()); assertTrue(diagnosticsText().contains("Duplicate async request id"), diagnosticsText());
    }
    /** The annotation's response class cannot disagree with the actual generic result. */
    @Test void compile_responseTypeMismatch_rejects() throws IOException {
        source("consumer.Bad", unit("consumer:bad", "public record Bad() implements AsyncRequest<Long> { public void handle(Object c,Object r) {} }"));
        assertFalse(compile()); assertTrue(diagnosticsText().contains("must match"), diagnosticsText());
    }
    /** A raw request contract has no safe response type and cannot be indexed. */
    @Test void compile_rawRequest_rejects() throws IOException {
        source("consumer.Bad", unit("consumer:bad", "public record Bad() implements AsyncRequest { public void handle(Object c,Object r) {} }"));
        assertFalse(compile()); assertTrue(diagnosticsText().contains("must match"), diagnosticsText());
    }
    /** Inherited specialization works without repeating the generic contract on the annotated class. */
    @Test void compile_genericSuperclass_resolvesConcreteResponse() throws IOException {
        source("consumer.Base", imports() + "public abstract class Base<T> implements AsyncRequest<T> { public void handle(Object c,Object r) {} }");
        source("consumer.Concrete", unit("consumer:concrete", "public class Concrete extends Base<String> {}"));
        assertTrue(compile(), diagnosticsText()); assertEquals("consumer.Concrete\n", Files.readString(index()));
    }
    /** Mutable data classes cannot hide final instance fields from the generated field plan. */
    @Test void compile_finalClassField_rejects() throws IOException {
        source("consumer.Bad", unit("consumer:bad", "public class Bad implements AsyncRequest<String> { private final int value=1; public void handle(Object c,Object r) {} }"));
        assertFalse(compile()); assertTrue(diagnosticsText().contains("Final serialized"), diagnosticsText());
    }
    /** Record results can be raw byte arrays larger than the ECS collection element limit. */
    @Test void compile_arrayResponse_writesIndex() throws IOException {
        source("consumer.Bytes", imports() + "@NetworkRequest(id=\"consumer:bytes\",side=Side.SERVER,response=byte[].class) public record Bytes(byte[] data) implements AsyncRequest<byte[]> { public void handle(Object c,Object r) {} }");
        assertTrue(compile(), diagnosticsText()); assertEquals("consumer.Bytes\n", Files.readString(index()));
    }
    /** Removing the final request produces an empty index for incremental builds. */
    @Test void compile_removedLastRequest_rewritesEmptyIndex() throws IOException {
        source("consumer.One", unit("consumer:one", "public record One() implements AsyncRequest<String> { public void handle(Object c,Object r) {} }"));
        assertTrue(compile(), diagnosticsText()); source("consumer.One", "package consumer; public record One() {}");
        assertTrue(compile(), diagnosticsText()); assertEquals("", Files.readString(index()));
    }
    /** @param id request id @param declaration source declaration @return complete fixture source */
    private String unit(String id, String declaration) { return imports() + "@NetworkRequest(id=\"" + id + "\",side=Side.SERVER,response=String.class) " + declaration; }
    /** @return common source imports */
    private String imports() { return "package consumer; import " + API + ".*; import " + API + ".annotations.*; "; }
    /** @return generated request index */
    private Path index() { return directory.resolve("classes/META-INF/sdmal/network-requests"); }
    /** @return diagnostics as text */
    private String diagnosticsText() { return diagnostics.getDiagnostics().toString(); }
    /** @param name source name @param content source contents @throws IOException file error */
    private void source(String name, String content) throws IOException { var path = directory.resolve("src/" + name.replace('.', '/') + ".java"); Files.createDirectories(path.getParent()); Files.writeString(path, content); }
    /** @return compiler success @throws IOException source read error */
    private boolean compile() throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler(); diagnostics = new DiagnosticCollector<>(); Files.createDirectories(directory.resolve("classes"));
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null); var files = Files.walk(directory.resolve("src"))) {
            var inputs = manager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java")).sorted().toList());
            var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "17", "-d", directory.resolve("classes").toString(), "-classpath", directory.resolve("empty").toString()), null, inputs);
            task.setProcessors(List.of(new AsyncRequestProcessor())); return task.call();
        }
    }
}
