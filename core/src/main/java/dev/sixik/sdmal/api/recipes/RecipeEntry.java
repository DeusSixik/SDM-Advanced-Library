package dev.sixik.sdmal.api.recipes;

import java.util.Objects;

/**
 * One prepared recipe. Values must be deeply immutable or explicitly safe for concurrent reads;
 * the library copies index structures, not arbitrary user objects. Never wrap a live native Recipe.
 *
 * @param id globally unique recipe id within one manager
 * @param type shared typed category token
 * @param value immutable detached recipe data
 * @param <R> prepared data type
 */
public record RecipeEntry<R>(String id, RecipeType<?, R> type, R value) {
    /**
     * Validates the immutable recipe envelope.
     *
     * @param id namespaced recipe id
     * @param type shared category token
     * @param value immutable prepared value
     */
    public RecipeEntry {
        validateId(id);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    /**
     * Validates a portable recipe or category id without Minecraft registry access.
     *
     * @param id non-null lowercase namespaced id
     * @return validated id
     */
    static String validateId(String id) {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Expected lowercase namespaced recipe id: " + id);
        }
        return id;
    }
}
