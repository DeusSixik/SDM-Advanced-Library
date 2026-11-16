package dev.sixik.sdmal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises shared registry helpers against real Minecraft classes for each supported version. */
class SDMAdvancedLibraryTest {
    /** Initializes vanilla registries before exercising content factories. */
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Registry identifiers must retain the mod namespace on every mapping version. */
    @Test void id_examplePath_usesModNamespace() {
        assertEquals("sdmal:example_block", SDMAdvancedLibrary.id("example_block").toString());
    }

    /**
     * Rejects invalid identifiers and missing items without crashing configuration validation.
     * @param name malformed or unregistered item identifier
     */
    @ParameterizedTest @ValueSource(strings = {"INVALID NAME", "minecraft:not_a_real_item", "sdmal:missing", ""})
    void isItem_invalidResourceLocation_returnsFalse(String name) {
        assertFalse(SDMAdvancedLibrary.isItem(name));
    }

    /** Known items must pass registry validation; non-string values must be rejected. */
    @Test void isItem_registeredItemAndWrongTypes_validatesSafely() {
        assertTrue(SDMAdvancedLibrary.isItem("minecraft:iron_ingot"));
        assertFalse(SDMAdvancedLibrary.isItem(null));
        assertFalse(SDMAdvancedLibrary.isItem(42));
    }
}
