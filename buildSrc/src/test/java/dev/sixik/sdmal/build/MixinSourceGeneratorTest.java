package dev.sixik.sdmal.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies directory-selected implementations and automatic module and side registration. */
class MixinSourceGeneratorTest {
    /** Isolated sources and output files supplied by JUnit for each invocation. */
    @TempDir Path directory;

    /**
     * Combines directory selectors with generation to select exactly one implementation per target.
     * @param version numeric target Minecraft version
     * @param expected group whose source must appear in the generated output
     * @throws IOException if fixture or generated files cannot be accessed
     */
    @ParameterizedTest
    @CsvSource({"1.19.2, 1.19-1.21", "1.20.1, 1.19-1.21", "1.21.1, 1.19-1.21",
        "26.1.2, 26.1", "26.3, 26.3"})
    void generate_versionedDirectories_selectsOneImplementation(String version, String expected) throws IOException {
        var sources = List.of(
            source("1.19-1.21", "Example", "impl", "", "legacy"),
            source("26.1", "Example", "impl", "", "modern"),
            source("26.3", "Example", "impl", "", "future"));
        List<Path> selected = sources.stream()
            .filter(path -> VersionSelector.matches(version, path.getParent().getFileName().toString())).toList();
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(), selected, version, "dev.sixik.sdmal", "sdmal", 25, output);
        String java = Files.readString(output.resolve("java/dev/sixik/sdmal/impl/mixin/Example.java"));
        String expectedSource = Files.readString(directory.resolve(expected).resolve("Example.java"));
        assertEquals(expectedSource, java);
        assertEquals(1, config(output, "impl").getAsJsonArray("mixins").size());
        assertEquals("Example", config(output, "impl").getAsJsonArray("mixins").get(0).getAsString());
        assertEquals("JAVA_21", config(output, "impl").get("compatibilityLevel").getAsString());
    }

    /**
     * Uses only the immediate client/server packages as side markers, with stable alphabetical lists.
     * @throws IOException if fixture or generated files cannot be accessed
     */
    @Test void generate_moduleAndSidePackages_writesSeparateSortedConfigs() throws IOException {
        Path core = source("core", "CoreExample", "core", "", "core");
        Path server = source("impl", "ServerExample", "impl", "server", "server");
        Path client = source("impl", "ClientExample", "impl", "client.render", "client");
        Path common = source("impl", "ACommon", "impl", "", "common");
        Path last = source("impl", "ZCommon", "impl", "", "last");
        Path nestedClient = source("impl", "NestedClient", "impl", "feature.client", "common");
        Path clientPrefix = source("impl", "ClientPrefix", "impl", "clienttools", "common");
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(core), List.of(server, client, last, nestedClient, clientPrefix, common),
            "1.20.1", "dev.sixik.sdmal", "sdmal", 17, output);
        JsonObject coreJson = config(output, "core");
        JsonObject implJson = config(output, "impl");
        assertEquals("dev.sixik.sdmal.core.mixin", coreJson.get("package").getAsString());
        assertEquals(JsonParser.parseString("[\"CoreExample\"]"), coreJson.getAsJsonArray("mixins"));
        assertEquals(JsonParser.parseString("[\"ACommon\",\"ZCommon\",\"clienttools.ClientPrefix\",\"feature.client.NestedClient\"]"),
            implJson.getAsJsonArray("mixins"));
        assertEquals(JsonParser.parseString("[\"client.render.ClientExample\"]"), implJson.getAsJsonArray("client"));
        assertEquals(JsonParser.parseString("[\"server.ServerExample\"]"), implJson.getAsJsonArray("server"));
        assertTrue(coreJson.get("required").getAsBoolean());
        assertEquals(1, implJson.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        assertTrue(implJson.getAsJsonObject("overwrites").get("requireAnnotations").getAsBoolean());
        assertEquals("JAVA_17", implJson.get("compatibilityLevel").getAsString());
    }

    /**
     * Preserves the source and registration of an ordinary mixin without selection metadata.
     * @throws IOException if fixture or generated files cannot be accessed
     */
    @Test void generate_plainMixin_preservesSourceAndRegistersClass() throws IOException {
        Path mixin = source("impl", "Example", "impl", "", "shared");
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(), List.of(mixin), "26.3", "dev.sixik.sdmal", "sdmal", 25, output);
        assertEquals(Files.readString(mixin),
            Files.readString(output.resolve("java/dev/sixik/sdmal/impl/mixin/Example.java")));
        assertEquals(JsonParser.parseString("[\"Example\"]"), config(output, "impl").getAsJsonArray("mixins"));
    }

    /**
     * Copies implementation helpers without adding them to the mixin configuration.
     * @throws IOException if fixture or generated files cannot be accessed
     */
    @Test void generate_regularJavaClass_copiesWithoutRegistration() throws IOException {
        Path plain = directory.resolve("Plain.java");
        Files.writeString(plain, "package dev.sixik.sdmal.impl; public class Plain { }");
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(), List.of(plain), "1.20.1", "dev.sixik.sdmal", "sdmal", 17, output);
        assertEquals(Files.readString(plain), Files.readString(output.resolve("java/dev/sixik/sdmal/impl/Plain.java")));
        assertTrue(config(output, "impl").getAsJsonArray("mixins").isEmpty());
    }

    /**
     * Rejects overlapping directory implementations instead of arbitrarily choosing a source file.
     * @throws IOException if fixture files cannot be accessed
     */
    @Test void generate_overlappingDirectories_reportsBothSources() throws IOException {
        Path first = source("1.20", "Example", "impl", "", "first");
        Path second = source("1.19-1.21", "Example", "impl", "", "second");
        var error = assertThrows(IllegalArgumentException.class, () -> MixinSourceGenerator.generate(
            List.of(), List.of(first, second), "1.20.1", "dev.sixik.sdmal", "sdmal", 17, directory.resolve("output")));
        assertTrue(error.getMessage().contains(first.toString()));
        assertTrue(error.getMessage().contains(second.toString()));
    }

    /**
     * Rejects mixins outside both supported namespaces, including misleading package prefixes.
     * @throws IOException if fixture files cannot be accessed
     */
    @Test void generate_wrongMixinPackage_reportsSupportedPackages() throws IOException {
        Path source = source("core", "Example", "core", "", "core");
        Files.writeString(source, Files.readString(source).replace("dev.sixik.sdmal.core.mixin",
            "dev.sixik.sdmal.impl.mixintools"));
        var error = assertThrows(IllegalArgumentException.class, () -> MixinSourceGenerator.generate(
            List.of(), List.of(source), "1.20.1", "dev.sixik.sdmal", "sdmal", 17, directory.resolve("output")));
        assertTrue(error.getMessage().contains("dev.sixik.sdmal.core.mixin or dev.sixik.sdmal.impl.mixin"));
    }

    /**
     * Registers ECS implementation mixins stored in core under impl on every supported game version.
     * @param version target Minecraft version
     * @throws IOException if fixture or generated files cannot be accessed
     */
    @ParameterizedTest
    @CsvSource({"1.19.2", "1.20.1", "1.21.1", "26.1.2", "26.3"})
    void generate_implMixinsInCoreSources_registersByPackage(String version) throws IOException {
        String name = "Mixin$ECS$Level$objectManager";
        Path common = source("core", name, "impl", "ecs", "common");
        Path client = source("core", "ClientEcs", "impl", "client.ecs", "client");
        Path server = source("core", "ServerEcs", "impl", "server.ecs", "server");
        Path regularImpl = source("impl", "OtherImpl", "impl", "", "other");
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(common, client, server), List.of(regularImpl), version,
            "dev.sixik.sdmal", "sdmal", 17, "sdmal.refmap.json", output);
        JsonObject impl = config(output, "impl");
        assertEquals("dev.sixik.sdmal.impl.mixin", impl.get("package").getAsString());
        assertEquals(JsonParser.parseString("[\"OtherImpl\",\"ecs." + name + "\"]"), impl.getAsJsonArray("mixins"));
        assertEquals(JsonParser.parseString("[\"client.ecs.ClientEcs\"]"), impl.getAsJsonArray("client"));
        assertEquals(JsonParser.parseString("[\"server.ecs.ServerEcs\"]"), impl.getAsJsonArray("server"));
        assertEquals("sdmal.refmap.json", impl.get("refmap").getAsString());
        for (String side : List.of("mixins", "client", "server")) {
            assertTrue(config(output, "core").getAsJsonArray(side).isEmpty());
        }
        assertEquals(Files.readString(common),
            Files.readString(output.resolve("java/dev/sixik/sdmal/impl/mixin/ecs/" + name + ".java")));
    }

    /**
     * Merges registrations for core from both physical modules instead of overwriting either batch.
     * @throws IOException if fixture or generated files cannot be accessed
     */
    @Test void generate_coreMixinsInImplSources_mergesBothBatches() throws IOException {
        Path first = source("core", "First", "core", "", "first");
        Path second = source("impl", "Second", "core", "", "second");
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(first), List.of(second), "1.20.1",
            "dev.sixik.sdmal", "sdmal", 17, output);
        assertEquals(JsonParser.parseString("[\"First\",\"Second\"]"), config(output, "core").getAsJsonArray("mixins"));
        assertTrue(config(output, "impl").getAsJsonArray("mixins").isEmpty());
    }

    /**
     * Rejects the same implementation class supplied by two physical modules.
     * @throws IOException if fixture files cannot be accessed
     */
    @Test void generate_duplicateAcrossModules_reportsBothSources() throws IOException {
        Path first = source("core", "Example", "impl", "ecs", "first");
        Path second = source("impl", "Example", "impl", "ecs", "second");
        var error = assertThrows(IllegalArgumentException.class, () -> MixinSourceGenerator.generate(
            List.of(first), List.of(second), "1.20.1", "dev.sixik.sdmal", "sdmal", 17, directory.resolve("output")));
        assertTrue(error.getMessage().contains(first.toString()));
        assertTrue(error.getMessage().contains(second.toString()));
    }

    /**
     * Rejects multiple top-level types sharing a mixin source file with a clear diagnostic.
     * @throws IOException if fixture files cannot be accessed
     */
    @Test void generate_multipleTopLevelTypes_reportsUnsupportedLayout() throws IOException {
        Path source = source("impl", "Example", "impl", "", "impl");
        Files.writeString(source, Files.readString(source) + "class AnotherType { }");
        var error = assertThrows(IllegalArgumentException.class, () -> MixinSourceGenerator.generate(
            List.of(), List.of(source), "1.20.1", "dev.sixik.sdmal", "sdmal", 17, directory.resolve("output")));
        assertTrue(error.getMessage().contains("one top-level mixin per source file"));
    }

    /**
     * Keeps valid empty configurations for modules that currently contain no mixins.
     * @throws IOException if generated files cannot be accessed
     */
    @Test void generate_noMixins_writesEmptySectionsWithoutRefmap() throws IOException {
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(), List.of(), "26.3", "dev.sixik.sdmal", "sdmal", 25, output);
        for (String module : List.of("core", "impl")) {
            JsonObject json = config(output, module);
            for (String section : List.of("mixins", "client", "server")) {
                assertTrue(json.getAsJsonArray(section).isEmpty());
            }
            assertFalse(json.has("refmap"));
        }
    }

    /**
     * Connects both configurations to the refmap produced by the single loader compilation.
     * @throws IOException if generated files cannot be accessed
     */
    @Test void generate_legacyForgeRefmap_connectsBothConfigs() throws IOException {
        Path output = directory.resolve("output");
        MixinSourceGenerator.generate(List.of(), List.of(), "1.20.1", "dev.sixik.sdmal", "sdmal", 17,
            "sdmal.refmap.json", output);
        assertEquals("sdmal.refmap.json", config(output, "core").get("refmap").getAsString());
        assertEquals("sdmal.refmap.json", config(output, "impl").get("refmap").getAsString());
    }

    /**
     * Writes a parse-only fixture; Minecraft and Mixin classes need not resolve during parsing.
     * @param group fixture directory separating alternative implementations
     * @param name simple Java class name
     * @param module core or impl
     * @param subpackage optional package below the module's mixin namespace, empty for the root
     * @param marker text distinguishing the selected implementation
     * @return newly created Java source file
     * @throws IOException if fixture files cannot be written
     */
    private Path source(String group, String name, String module, String subpackage, String marker) throws IOException {
        Path source = directory.resolve(group).resolve(name + ".java");
        Files.createDirectories(source.getParent());
        String namespace = "dev.sixik.sdmal." + module + ".mixin" + (subpackage.isEmpty() ? "" : "." + subpackage);
        Files.writeString(source, "package " + namespace + ";\n"
            + "@Mixin(Target.class)\npublic class " + name
            + " { static final String MARKER = \"" + marker + "\"; }\n");
        return source;
    }

    /**
     * Loads generated configuration through an independent JSON parser.
     * @param output generation output root
     * @param module core or impl
     * @return parsed configuration object
     * @throws IOException if the JSON file cannot be read
     */
    private JsonObject config(Path output, String module) throws IOException {
        return JsonParser.parseString(Files.readString(output.resolve("resources/sdmal." + module + ".mixins.json")))
            .getAsJsonObject();
    }
}
