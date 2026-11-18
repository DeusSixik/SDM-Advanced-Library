package dev.sixik.sdmal.example.repository;

import dev.sixik.sdmal.api.repository.Repositories;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the documented partial-update scenario on the bundled H2 backend.
 */
class PartialUpdateRepositoryExampleTest {
    /**
     * The stale edit is rejected and the catalog rename preserves the committed order.
     */
    @Test
    void run_fieldUpdatesAndConflict_preservesCommittedData() {
        try (var manager = Repositories.h2("jdbc:h2:mem:partial_example_test_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")) {
            var result = PartialUpdateRepositoryExample.run(manager);
            assertEquals(20, result.value().order());
            assertEquals("building_renamed", result.value().catalogId());
            assertNotNull(result.value().id());
            assertNotNull(result.revision());
        }
    }
}
