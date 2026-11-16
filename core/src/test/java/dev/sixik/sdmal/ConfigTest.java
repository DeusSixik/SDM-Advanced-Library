package dev.sixik.sdmal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies default creation, persisted values and invalid configuration handling. */
class ConfigTest {
    /** Isolated temporary configuration directory supplied by JUnit. */
    @TempDir Path directory;

    /** Missing files must be created with the original defaults and survive a reload. */
    @Test void load_missingFile_createsReloadableDefaults() throws IOException {
        Path file = directory.resolve("nested/sdmal-common.json");
        assertEquals(Config.DEFAULT, Config.load(file));
        assertTrue(Files.isRegularFile(file));
        assertEquals(Config.DEFAULT, Config.load(file));
    }

    /** Explicitly saved values must replace defaults without losing item identifiers. */
    @Test void load_customValues_preservesConfiguration() throws IOException {
        Path file = directory.resolve("config.json");
        Files.writeString(file, """
            {"logDirtBlock":false,"magicNumber":2147483647,
             "magicNumberIntroduction":"Number: ","items":["minecraft:stone"]}
            """);
        assertEquals(new Config(false, Integer.MAX_VALUE, "Number: ", List.of("minecraft:stone")), Config.load(file));
    }

    /** Partial JSON files inherit defaults for omitted fields. */
    @Test void load_partialValues_keepsMissingDefaults() throws IOException {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"magicNumber\":0,\"items\":[]}");
        assertEquals(new Config(true, 0, Config.DEFAULT.magicNumberIntroduction(), List.of()), Config.load(file));
    }

    /**
     * Invalid files must fail with their original contents preserved for correction.
     * @param content malformed or invalid configuration content
     */
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{\"magicNumber\":-1}", "{\"items\":null}", "{\"items\":[null]}"})
    void load_invalidConfiguration_reportsFailureAndPreservesFile(String content) throws IOException {
        Path file = directory.resolve("invalid.json");
        Files.writeString(file, content);
        assertThrows(IOException.class, () -> Config.load(file));
        assertEquals(content, Files.readString(file));
    }

    /** Caller-owned lists must not be able to mutate an existing configuration. */
    @Test void constructor_mutableList_copiesAndProtectsItems() {
        List<String> items = new ArrayList<>(List.of("minecraft:stone"));
        Config config = new Config(true, 42, "", items);
        items.clear();
        assertEquals(List.of("minecraft:stone"), config.items());
        assertThrows(UnsupportedOperationException.class, () -> config.items().clear());
    }
}
