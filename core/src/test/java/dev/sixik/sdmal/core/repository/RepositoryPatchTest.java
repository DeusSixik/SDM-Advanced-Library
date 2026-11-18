package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.api.repository.RepositoryPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Input validation and backend capability checks independent of a database server.
 */
class RepositoryPatchTest {
    /**
     * Temporary filesystem repository root.
     */
    @TempDir Path directory;

    /**
     * Unsafe field paths cannot become SQL identifiers or MongoDB update paths.
     */
    @Test
    void fields_invalidNames_rejectsPathsAndOperators() {
        for (String name : new String[] {"", ".", "price.total", "$set", "a\0b", "a b", "1price", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> new RepositoryPatch().set(name, 1), name);
            assertThrows(IllegalArgumentException.class, () -> new RepositoryPatch().unset(name), name);
        }
        assertDoesNotThrow(() -> new RepositoryPatch().set("_price2", 1));
        assertDoesNotThrow(() -> new RepositoryPatch().set("x".repeat(128), 1));
    }

    /**
     * Custom fragments must be strict complete JSON, excluding nonfinite numbers and trailing data.
     */
    @Test
    void setJson_invalidFragments_rejectsLenientInput() {
        for (String json : new String[] {"", " ", "NaN", "Infinity", "{a:1}", "[1,]", "1 2", "/*x*/1", "'text'"}) {
            assertThrows(IllegalArgumentException.class, () -> new RepositoryPatch().setJson("field", json), json);
        }
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPatch().set("field", Double.NaN));
        assertEquals("null", new RepositoryPatch().setJson("field", "null").changes().get("field"));
        assertEquals("null", new RepositoryPatch().set("field", null).changes().get("field"));
    }

    /**
     * Captured operations are independent from subsequent builder mutations.
     */
    @Test
    void changes_snapshot_isImmutableAndRetainsRemovalMarkers() {
        var patch = new RepositoryPatch().set("price", 5).unset("note");
        var captured = patch.changes();
        patch.set("price", 7).set("note", "new");
        assertEquals("5", captured.get("price"));
        assertTrue(captured.containsKey("note"));
        assertNull(captured.get("note"));
        assertThrows(UnsupportedOperationException.class, () -> captured.put("other", "1"));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPatch().changes());
    }

    /**
     * Files cannot claim database partial-write semantics through a full rewrite fallback.
     */
    @Test
    void fileManagers_openPatchable_reportsUnsupportedCapability() {
        try (var json = Repositories.json(directory.resolve("json"));
                var nbt = Repositories.nbt(directory.resolve("nbt"))) {
            assertThrows(UnsupportedOperationException.class,
                    () -> json.openPatchable("shops", PatchRepositoryContract.DEFINITION));
            assertThrows(UnsupportedOperationException.class,
                    () -> nbt.openPatchable("shops", PatchRepositoryContract.DEFINITION));
        }
    }
}
