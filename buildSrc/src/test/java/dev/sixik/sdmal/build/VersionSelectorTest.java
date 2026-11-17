package dev.sixik.sdmal.build;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers prefix boundaries, inclusive ranges and invalid selectors independently of Gradle. */
class VersionSelectorTest {
    /**
     * Verifies numerical comparisons and inclusion of all patch releases in a selected prefix.
     * @param version complete target version
     * @param selector numeric prefix or inclusive range
     * @param expected expected membership result
     */
    @ParameterizedTest
    @CsvSource({
        "1.19.2, 1.19-1.21, true", "1.20.1, 1.19-1.21, true", "1.21.1, 1.19-1.21, true",
        "1.21.99, 1.19-1.21, true", "1.18.2, 1.19-1.21, false", "1.22, 1.19-1.21, false",
        "26.1.2, 1.19-1.21, false", "26.1.2, 26.1, true", "26.3, 26.1, false",
        "26.3, 26.3, true", "26.3.1, 26.3, true", "26.30, 26.3, false",
        "1.20.1, 1.20.1, true", "1.20.2, 1.20.1, false", "1.19.1, 1.19.2-1.21.1, false",
        "1.21.2, 1.19.2-1.21.1, false", "1.21.1, 1.19.2-1.21.1, true",
        "1.9.4, 1.9-1.10, true", "1.10.2, 1.9-1.10, true", "1.11, 1.9-1.10, false",
        "26.1.2, 26.1-26.3, true", "26.3, 26.1-26.3, true", "26.4, 26.1-26.3, false"
    })
    void matches_prefixAndRangeBoundaries_returnsMembership(String version, String selector, boolean expected) {
        assertEquals(expected, VersionSelector.matches(version, selector));
    }

    /**
     * Rejects typos instead of silently omitting a mixin from production artifacts.
     * @param selector invalid, reversed, unsupported, or overflowing selector
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "-", "1..20", "1.21-1.19", "26.3-26.1", "1.19-1.20-1.21",
        "1.20-", "-1.20", "1.20.x", "latest", "1.21-pre1", "999999999999.1"})
    void matches_invalidSelector_reportsError(String selector) {
        assertThrows(IllegalArgumentException.class, () -> VersionSelector.matches("1.20.1", selector));
    }
}
