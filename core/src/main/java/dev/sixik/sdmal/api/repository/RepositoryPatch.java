package dev.sixik.sdmal.api.repository;

import dev.sixik.sdmal.core.repository.RepositoryFields;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * Collects replacements and removals of top-level JSON fields. Names refer to the codec's JSON
 * properties, not necessarily Java field names. Nested objects and arrays are replaced as a unit.
 * A builder is not thread-safe; repeated operations on the same field use the last operation.
 */
public final class RepositoryPatch {
    /**
     * JSON fragments by field name; a Java null marks removal, while "null" stores JSON null.
     */
    private final Object2ObjectOpenHashMap<String, String> changes = new Object2ObjectOpenHashMap<>();

    /**
     * Encodes a replacement with the library's Gson serializer. Use setJson for custom codecs.
     *
     * @param field top-level JSON name, matching [A-Za-z_][A-Za-z0-9_]{0,127}
     * @param value replacement value; null explicitly stores JSON null
     * @return this builder
     */
    public RepositoryPatch set(String field, Object value) {
        return setJson(field, RepositoryFields.toJson(value));
    }

    /**
     * Sets a field to one strict JSON value without involving Gson reflection on the application type.
     *
     * @param field top-level JSON name
     * @param json complete JSON value, including "null" when desired
     * @return this builder
     * @throws IllegalArgumentException for an unsafe field name or invalid JSON
     */
    public RepositoryPatch setJson(String field, String json) {
        RepositoryFields.validateName(field);
        changes.put(field, RepositoryFields.normalize(json));
        return this;
    }

    /**
     * Removes a field. Removing an absent field is allowed; the decoded result must retain its key.
     *
     * @param field top-level JSON name
     * @return this builder
     */
    public RepositoryPatch unset(String field) {
        RepositoryFields.validateName(field);
        changes.put(field, null);
        return this;
    }

    /**
     * Captures stable operations for a backend. Application code should use set/unset instead.
     *
     * @return immutable copy; null values mean removal
     * @throws IllegalArgumentException when no operations were specified
     */
    @ApiStatus.Internal
    public Map<String, String> changes() {
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("A repository patch must change at least one field");
        }
        return Object2ObjectMaps.unmodifiable(new Object2ObjectOpenHashMap<>(changes));
    }
}
