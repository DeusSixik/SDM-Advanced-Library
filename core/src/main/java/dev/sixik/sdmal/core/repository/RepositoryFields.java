package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.internal.driver.gson.Gson;
import dev.sixik.sdmal.internal.driver.gson.GsonBuilder;
import dev.sixik.sdmal.internal.driver.gson.JsonElement;
import dev.sixik.sdmal.internal.driver.gson.JsonObject;
import dev.sixik.sdmal.internal.driver.gson.Strictness;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Lossless top-level JSON fragments shared by SQL and MongoDB field stores. JSON text avoids
 * BSON numeric narrowing and keeps custom JSON values identical across database backends.
 */
@ApiStatus.Internal
public final class RepositoryFields {
    /**
     * Strict JSON parser and field-value serializer; explicit nulls remain present.
     */
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setStrictness(Strictness.STRICT).create();

    /**
     * Portable top-level identifiers accepted by both SQL rows and MongoDB update paths.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /**
     * Prevents construction.
     */
    private RepositoryFields() {}

    /**
     * Validates one portable top-level property name.
     *
     * @param field non-null field name, at most 128 ASCII characters
     * @throws IllegalArgumentException for a dotted, dollar-prefixed, or otherwise unsafe name
     */
    public static void validateName(String field) {
        Objects.requireNonNull(field, "field");
        if (!NAME.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid top-level repository field: " + field);
        }
    }

    /**
     * Serializes one application replacement, preserving explicit null.
     *
     * @param value nullable JSON-compatible value
     * @return complete JSON fragment
     */
    public static String toJson(Object value) {
        return JSON.toJson(value);
    }

    /**
     * Parses strict JSON and removes insignificant whitespace.
     *
     * @param json complete JSON value
     * @return normalized fragment
     */
    public static String normalize(String json) {
        return parse(json).toString();
    }

    /**
     * Splits a JSON object into independently writable top-level fragments.
     *
     * @param json application codec output
     * @return mutable map preserving JSON types and numeric spelling
     * @throws IllegalArgumentException if the root is not an object or names are unsafe
     */
    public static Map<String, String> split(String json) {
        JsonElement root = parse(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Field updates require a JSON-object codec; opaque NBT is unsupported");
        }
        Map<String, String> fields = new Object2ObjectOpenHashMap<>();
        for (var entry : root.getAsJsonObject().entrySet()) {
            validateName(entry.getKey());
            fields.put(entry.getKey(), entry.getValue().toString());
        }
        return fields;
    }

    /**
     * Applies captured operations to an independent field map.
     *
     * @param source current complete document
     * @param changes replacements or Java null removals
     * @return patched independent document
     */
    public static Map<String, String> apply(Map<String, String> source, Map<String, String> changes) {
        Map<String, String> result = new Object2ObjectOpenHashMap<>(source);
        for (var entry : changes.entrySet()) {
            if (entry.getValue() == null) {
                result.remove(entry.getKey());
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Decodes a complete field document and checks its persisted identity.
     *
     * @param definition application key and JSON codec
     * @param key encoded database key
     * @param fields complete JSON fragments
     * @param <K> key type
     * @param <V> value type
     * @return decoded candidate with matching identity
     */
    public static <K, V> V decode(
            RepositoryDefinition<K, V> definition, String key, Map<String, String> fields) {
        JsonObject json = new JsonObject();
        for (var entry : fields.entrySet()) {
            validateName(entry.getKey());
            json.add(entry.getKey(), parse(entry.getValue()));
        }
        V value = Objects.requireNonNull(definition.codec().decode(json.toString()), "decoded value");
        definition.verifyEntityKey(definition.decodeKey(key), value);
        return value;
    }

    /**
     * Requires exactly one nonempty strict JSON value.
     *
     * @param json source text
     * @return parsed tree, including JsonNull for the literal null
     */
    private static JsonElement parse(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonElement value = JSON.fromJson(json, JsonElement.class);
            if (value == null) {
                throw new IllegalArgumentException("Empty repository JSON value");
            }
            return value;
        } catch (dev.sixik.sdmal.internal.driver.gson.JsonParseException failure) {
            throw new IllegalArgumentException("Invalid repository JSON value", failure);
        }
    }
}
