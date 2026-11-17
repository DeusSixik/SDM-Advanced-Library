package dev.sixik.sdmal.build;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Registers mixins from already selected source directories without resolving Minecraft types. */
public final class MixinSourceGenerator {
    /** Prevents instantiation of the build utility. */
    private MixinSourceGenerator() {}

    /**
     * Generates selected sources plus one configuration for each module.
     * @param coreSources preprocessed core Java files; mixin packages select their configuration
     * @param implSources preprocessed impl and loader Java files; mixin packages select their configuration
     * @param version numeric target Minecraft version
     * @param packageRoot Java namespace containing core.mixin and impl.mixin
     * @param modId identifier used in configuration file names
     * @param javaVersion target Java language version; Mixin levels are capped at 21
     * @param output empty output directory owned by the caller
     * @throws IOException if input or output files cannot be accessed
     * @throws IllegalArgumentException if source syntax or mixin packages are invalid or sources conflict
     */
    public static void generate(List<Path> coreSources, List<Path> implSources, String version,
                                String packageRoot, String modId, int javaVersion, Path output) throws IOException {
        generate(coreSources, implSources, version, packageRoot, modId, javaVersion, "", output);
    }

    /**
     * Generates sources and configs, optionally referencing a shared loader-generated refmap.
     * @param coreSources preprocessed core Java files; mixin packages select their configuration
     * @param implSources preprocessed impl and loader Java files; mixin packages select their configuration
     * @param version numeric target Minecraft version
     * @param packageRoot Java namespace containing core.mixin and impl.mixin
     * @param modId identifier used in configuration file names
     * @param javaVersion target Java language version
     * @param refmap optional refmap file name, empty when bytecode remapping needs no refmap
     * @param output empty output directory owned by the caller
     * @throws IOException if input or output files cannot be accessed
     * @throws IllegalArgumentException if source syntax or mixin packages are invalid or sources conflict
     */
    public static void generate(List<Path> coreSources, List<Path> implSources, String version,
                                String packageRoot, String modId, int javaVersion, String refmap, Path output)
                                throws IOException {
        var destinations = new HashMap<Path, Path>();
        var configurations = new TreeMap<String, Map<String, TreeSet<String>>>();
        for (String module : List.of("core", "impl")) {
            var sections = new TreeMap<String, TreeSet<String>>();
            for (String side : List.of("mixins", "client", "server")) sections.put(side, new TreeSet<>());
            configurations.put(module, sections);
        }
        prepare(coreSources, version, packageRoot, output, destinations, configurations);
        prepare(implSources, version, packageRoot, output, destinations, configurations);
        for (var configuration : configurations.entrySet()) {
            writeConfiguration(configuration.getKey(), packageRoot, modId, javaVersion, refmap,
                output, configuration.getValue());
        }
    }

    /**
     * Copies selected sources and registers mixins by their Java package and runtime side.
     *
     * <p>The immediate {@code client} and {@code server} subpackages of the mixin package
     * select the matching config sections. All other subpackages apply on both sides.
     * Both mixin namespaces are supported in either physical source module.
     * @param sources Java sources from one physical module, possibly belonging to either config
     * @param version numeric target Minecraft version
     * @param packageRoot namespace preceding the module's mixin package
     * @param output generated output root
     * @param destinations shared map detecting overlapping selected source paths
     * @param configurations shared module/side registrations accumulated across all source modules
     * @throws IOException if files cannot be read or written
     * @throws IllegalArgumentException if source syntax or mixin packages are invalid or sources conflict
     */
    private static void prepare(List<Path> sources, String version, String packageRoot, Path output,
                                Map<Path, Path> destinations,
                                Map<String, Map<String, TreeSet<String>>> configurations)
                                throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required to prepare mixin sources");
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            for (Path source : sources.stream().distinct().sorted().toList()) {
                var diagnostics = new DiagnosticCollector<JavaFileObject>();
                var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none"), null, manager.getJavaFileObjects(source));
                var unit = task.parse().iterator().next();
                for (var diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        throw new IllegalArgumentException(source + ": " + diagnostic.getMessage(null));
                    }
                }
                String namespace = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                var names = new ArrayList<String>();
                String side = "mixins";
                String module = null;
                for (var declaration : unit.getTypeDecls()) {
                    if (!(declaration instanceof ClassTree type)) continue;
                    var annotations = type.getModifiers().getAnnotations();
                    boolean mixin = annotations.stream().anyMatch(a -> named(a, "Mixin"));
                    if (!mixin) continue;
                    if (unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).count() != 1) {
                        throw new IllegalArgumentException(source + ": use one top-level mixin per source file");
                    }
                    for (String candidate : configurations.keySet()) {
                        String candidatePackage = packageRoot + "." + candidate + ".mixin";
                        if (namespace.equals(candidatePackage) || namespace.startsWith(candidatePackage + ".")) {
                            module = candidate;
                            break;
                        }
                    }
                    if (module == null) throw new IllegalArgumentException(source + ": mixins must be below "
                        + packageRoot + ".core.mixin or " + packageRoot + ".impl.mixin");
                    String mixinPackage = packageRoot + "." + module + ".mixin";
                    String qualified = namespace + "." + type.getSimpleName();
                    String relativeName = qualified.substring(mixinPackage.length() + 1);
                    if (relativeName.startsWith("client.")) side = "client";
                    else if (relativeName.startsWith("server.")) side = "server";
                    names.add(relativeName);
                }
                Path relative = Path.of(namespace.replace('.', '/')).resolve(source.getFileName());
                Path previous = destinations.putIfAbsent(relative, source);
                if (previous != null) {
                    throw new IllegalArgumentException("Overlapping sources for " + version + ": " + previous + " and " + source);
                }
                Path destination = output.resolve("java").resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
                if (module != null) configurations.get(module).get(side).addAll(names);
            }
        }
    }

    /**
     * Writes one complete configuration after registration from every source module has finished.
     * @param module configuration namespace, core or impl
     * @param packageRoot namespace preceding the module's mixin package
     * @param modId configuration file name prefix
     * @param javaVersion target Java language version, capped at 21 for Mixin compatibility
     * @param refmap optional shared refmap file name
     * @param output generated output root
     * @param sections sorted mixin class names grouped by runtime side
     * @throws IOException if the configuration file cannot be written
     */
    private static void writeConfiguration(String module, String packageRoot, String modId, int javaVersion,
                                            String refmap, Path output, Map<String, TreeSet<String>> sections)
                                            throws IOException {
        String mixinPackage = packageRoot + "." + module + ".mixin";
        var json = new StringBuilder("{\n  \"required\": true,\n  \"minVersion\": \"0.8\",\n");
        json.append("  \"package\": \"").append(mixinPackage).append("\",\n");
        if (!refmap.isEmpty()) json.append("  \"refmap\": \"").append(refmap).append("\",\n");
        json.append("  \"compatibilityLevel\": \"JAVA_").append(Math.min(javaVersion, 21)).append("\",\n");
        for (String section : List.of("mixins", "client", "server")) {
            json.append("  \"").append(section).append("\": [");
            json.append(String.join(", ", sections.get(section).stream().map(name -> "\"" + name + "\"").toList()));
            json.append("],\n");
        }
        json.append("  \"injectors\": {\"defaultRequire\": 1},\n");
        json.append("  \"overwrites\": {\"requireAnnotations\": true}\n}\n");
        Path resources = output.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve(modId + "." + module + ".mixins.json"), json);
    }

    /**
     * Checks the simple name of an annotation without resolving its imported type.
     * @param annotation parsed annotation syntax
     * @param name expected simple annotation name
     * @return true for either simple or qualified spelling of the name
     */
    private static boolean named(AnnotationTree annotation, String name) {
        String actual = annotation.getAnnotationType().toString();
        return actual.equals(name) || actual.endsWith("." + name);
    }

}
