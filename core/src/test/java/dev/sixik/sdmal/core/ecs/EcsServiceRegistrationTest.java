package dev.sixik.sdmal.core.ecs;

import dev.sixik.sdmal.processor.EcsComponentProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real processor output through ServiceLoader and the runtime registry using separate consumer classes. */
class EcsServiceRegistrationTest {
    /** Isolated consumer build roots supplied by JUnit. */
    @TempDir Path directory;
    /** Component service descriptor name shared by the processor and runtime loader. */
    private static final String SERVICE = "META-INF/services/" + WorldObjectComponent.class.getName();

    /** Removes only registrations owned by these service-loading cases. */
    @AfterEach void cleanup() {
        WorldObjectComponentRegistry.instance().unregister("services_test:one");
        WorldObjectComponentRegistry.instance().unregister("services_test:duplicate");
    }

    /**
     * An external annotated class is registered by its service descriptor without invoking its constructor.
     * @throws Exception if fixture compilation, class loading or reflective counter inspection fails
     */
    @Test void discover_externalConsumer_registersIdempotentlyWithoutCreatingInstances() throws Exception {
        Path output = consumer("One", "services_test:one");
        try (var loader = serviceLoader(output)) {
            Class<? extends WorldObjectComponent> type = loader.loadClass("consumer.One").asSubclass(WorldObjectComponent.class);
            var registry = WorldObjectComponentRegistry.instance();
            assertEquals(1, registry.registerServices(loader));
            var descriptor = registry.require(type);
            assertEquals(0, type.getField("constructions").getInt(null));
            assertEquals(1, registry.registerServices(loader));
            assertSame(descriptor, registry.require(type));
            assertEquals(WorldObjectComponentType.DuplicatePolicy.REPLACE, descriptor.duplicatePolicy());
            assertEquals(type, descriptor.createDefault().getClass());
            assertEquals(1, type.getField("constructions").getInt(null));
        }
    }

    /**
     * Services from two independent consumer mods cannot silently overwrite the same archive identifier.
     * @throws Exception if fixture compilation or class loading fails
     */
    @Test void discover_twoModsWithSameId_reportsCollision() throws Exception {
        Path first = consumer("First", "services_test:duplicate");
        Path second = consumer("Second", "services_test:duplicate");
        try (var loader = serviceLoader(first, second)) {
            var failure = assertThrows(IllegalStateException.class,
                () -> WorldObjectComponentRegistry.instance().registerServices(loader));
            assertTrue(failure.getMessage().contains("services_test:duplicate"));
        }
    }

    /**
     * The ordinary entry point uses the current mod loader and safely tolerates an empty service index.
     * @throws Exception if the fixture descriptor cannot be written or loaded
     */
    @Test void discover_emptyIndexViaContextLoader_isNoOp() throws Exception {
        Path output = directory.resolve("empty");
        Path descriptor = output.resolve(SERVICE);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "");
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var loader = serviceLoader(output)) {
            thread.setContextClassLoader(loader);
            assertEquals(0, WorldObjectComponentRegistry.instance().registerServices());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Broken service entries fail at setup with their class name instead of silently omitting saved component types.
     * @throws Exception if the temporary consumer descriptor cannot be written
     */
    @Test void discover_missingProvider_failsWithServiceDiagnostic() throws Exception {
        Path output = directory.resolve("broken");
        Path descriptor = output.resolve(SERVICE);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "consumer.Missing\n");
        try (var loader = serviceLoader(output)) {
            var failure = assertThrows(ServiceConfigurationError.class,
                () -> WorldObjectComponentRegistry.instance().registerServices(loader));
            assertTrue(failure.getMessage().contains("consumer.Missing"));
        }
    }

    /**
     * Compiles a component in a separate consumer package using the public runtime API and real processor.
     * @param name simple consumer class name
     * @param id stable component id, permitting deliberate cross-mod collisions
     * @return compiled consumer root containing class files and its service descriptor
     * @throws Exception if source files or the compiler file manager cannot be opened
     */
    private Path consumer(String name, String id) throws Exception {
        Path root = directory.resolve(name);
        Path source = root.resolve("src/consumer/" + name + ".java");
        Path output = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(output);
        Files.writeString(source, "package consumer; import dev.sixik.sdmal.core.ecs.*;\n"
            + "@EcsComponent(id=\"" + id + "\", duplicatePolicy=WorldObjectComponentType.DuplicatePolicy.REPLACE)\n"
            + "public final class " + name + " extends AbstractWorldObjectComponent {\n"
            + "  public static int constructions;\n"
            + "  public " + name + "() { constructions++; }\n} ");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Service integration tests require a JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String api = Path.of(WorldObjectComponent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics,
                List.of("-classpath", api, "-d", output.toString()), null, manager.getJavaFileObjects(source));
            task.setProcessors(List.of(new EcsComponentProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        assertTrue(Files.exists(output.resolve(SERVICE)));
        return output;
    }

    /**
     * Loads consumer classes through the real API parent while isolating service metadata from other tests.
     * @param roots separately compiled consumer outputs
     * @return closeable loader exposing all supplied consumer service descriptors
     * @throws IOException if a consumer root cannot be converted to a URL
     */
    private URLClassLoader serviceLoader(Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int index = 0; index < roots.length; index++) urls[index] = roots[index].toUri().toURL();
        return new URLClassLoader(urls, WorldObjectComponent.class.getClassLoader()) {
            /** Loads only this fixture's provider indexes while retaining parent access to ordinary resources. */
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals(SERVICE) ? findResources(name) : super.getResources(name);
            }
        };
    }
}
