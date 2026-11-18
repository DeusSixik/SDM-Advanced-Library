package dev.sixik.sdmal.benchmark;

import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;

/**
 * Compares the frozen pre-cleanup library and the current implementation through equivalent
 * workloads compiled against their respective APIs. Both libraries use isolated child-first
 * loaders; only setup uses reflection, never measured operations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EcsCleanupBenchmark {
    /** Frozen implementation to compare; collections is captured immediately before that migration. */
    @Param({"cleanup"})
    public String baseline;
    /**
     * Workload kind, number of components/values, and arithmetic rounds per compute callback.
     */
    @Param({
        "owner:128:0",
        "owner:1024:0",
        "owner:1024:256",
        "worker:1024:0",
        "worker:1024:256",
        "legacy:1024:0",
        "commands:1024:0",
        "queries:1024:0",
        "wireWrite:128:0",
        "wireRead:128:0",
        "nbtWrite:128:0",
        "nbtRead:128:0",
        "delta:128:0",
        "deltaEmpty:128:0",
        "deltaSparse:128:0",
        "deltaRead:128:0"
    })
    public String workload;

    /**
     * Isolated frozen implementation and shared-interface workload.
     */
    private Workload before;

    /**
     * Isolated current implementation with the same setup and operation.
     */
    private Workload after;

    /**
     * Class loaders retained until trial cleanup.
     */
    private IsolatedLibrary beforeLoader, afterLoader;

    /**
     * Constructs equivalent worlds and verifies a matching observed result before measurement.
     *
     * @throws Exception if either implementation cannot initialize the workload
     */
    @Setup
    public void setup() throws Exception {
        URL baselineJar = getClass().getResource(this.baseline.equals("collections")
                ? "/collections-baseline/ecs-before-collections-1.20.1.jar"
                : "/baseline/ecs-before-cleanup-1.20.1.jar");
        if (baselineJar == null) {
            throw new IllegalStateException("Frozen ECS baseline resource is missing");
        }
        URL current =
                dev.sixik.sdmal.api.ecs.EcsObject.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation();
        URL fixtures = getClass().getProtectionDomain().getCodeSource().getLocation();
        var baselineFixtures = java.nio.file.Path.of(fixtures.toURI()).resolveSibling(
                this.baseline.equals("collections") ? "jmhCollectionsBaseline" : "jmhBaseline");
        if (!java.nio.file.Files.isDirectory(baselineFixtures))
            throw new IllegalStateException("Run through the jmhCleanup Gradle task");
        beforeLoader = new IsolatedLibrary(baselineJar, baselineFixtures.toUri().toURL());
        afterLoader = new IsolatedLibrary(current, fixtures);
        before = create(beforeLoader);
        after = create(afterLoader);
        long expected = before.run();
        long actual = after.run();
        if (expected != actual) {
            throw new AssertionError("Different workload results: " + expected + " != " + actual);
        }
        if (before.verificationHash() != after.verificationHash()) {
            throw new AssertionError("Different component state or serialized bytes: " + workload);
        }
    }

    /**
     * Instantiates the matching fixture build against one implementation.
     *
     * @param loader implementation loader
     * @return shared-interface workload without reflection in its measured operations
     * @throws Exception if fixture setup fails
     */
    private Workload create(ClassLoader loader) throws Exception {
        return (Workload)
                loader.loadClass("dev.sixik.sdmal.benchmark.fixture.EcsCleanupWorkload")
                        .getConstructor(String.class)
                        .newInstance(workload);
    }

    /**
     * Measures the exact library snapshot captured before this optimization task.
     *
     * @return observable committed state or encoded byte count
     */
    @Benchmark
    public long before() {
        return before.run();
    }

    /**
     * Measures the current library using the same workload and dispatch indirection.
     *
     * @return observable committed state or encoded byte count
     */
    @Benchmark
    public long after() {
        return after.run();
    }

    /**
     * Releases reference-counted buffers, worker threads, worlds and loaders outside measurement.
     *
     * @throws IOException if a loader cannot close
     */
    @TearDown
    public void close() throws IOException {
        try {
            if (before != null) before.close();
            if (after != null) after.close();
        } finally {
            if (beforeLoader != null) beforeLoader.close();
            if (afterLoader != null) afterLoader.close();
        }
    }

    /**
     * Shared parent-loader contract; implementations use identical bytecode in child loaders.
     */
    public interface Workload extends AutoCloseable {
        /**
         * Executes one complete measured operation.
         *
         * @return checksum, counter or byte count consumed by JMH
         */
        long run();

        /**
         * Verifies all component state and packet content outside measured operations.
         * @return deterministic state/content hash, independent of random object UUIDs
         */
        long verificationHash();

        /**
         * Releases resources belonging to this trial.
         */
        @Override
        void close();
    }

    /**
     * Isolates ECS classes and fixture implementations while sharing Minecraft, Netty and JMH
     * dependencies.
     */
    private static final class IsolatedLibrary extends URLClassLoader {
        /**
         * Opens one library and the common compiled fixture directory.
         *
         * @param library frozen JAR or current output directory
         * @param fixtures unchanged workload bytecode directory
         */
        IsolatedLibrary(URL library, URL fixtures) {
            super(new URL[] {library, fixtures}, EcsCleanupBenchmark.class.getClassLoader());
        }

        /**
         * Loads library-owned classes locally; third-party dependencies retain parent identity.
         *
         * @param name binary class name
         * @param resolve whether to link the class immediately
         * @return requested class
         * @throws ClassNotFoundException if no matching class exists
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("dev.sixik.sdmal.api.ecs.")
                    && !name.startsWith("dev.sixik.sdmal.core.ecs.")
                    && !name.startsWith("dev.sixik.sdmal.core.utils.")
                    && !name.startsWith("dev.sixik.sdmal.benchmark.fixture.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}
