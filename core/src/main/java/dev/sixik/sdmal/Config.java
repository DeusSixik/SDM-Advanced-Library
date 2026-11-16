package dev.sixik.sdmal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration shared by all loaders.
 * @param logDirtBlock whether common setup logs the dirt identifier
 * @param magicNumber nonnegative example number, up to {@link Integer#MAX_VALUE}
 * @param magicNumberIntroduction non-null prefix for the example number
 * @param items non-null list of item identifiers to log
 */
public record Config(boolean logDirtBlock, int magicNumber, String magicNumberIntroduction, List<String> items) {
    /** Default values matching the original Forge configuration. */
    public static final Config DEFAULT = new Config(true, 42, "The magic number is... ", List.of("minecraft:iron_ingot"));
    /** JSON codec used by the Fabric configuration file. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Validates and defensively copies configuration values.
     * @param logDirtBlock whether to log the dirt block
     * @param magicNumber example number in [0, Integer.MAX_VALUE]
     * @param magicNumberIntroduction non-null message prefix
     * @param items item identifier strings, without null entries
     * @throws IllegalArgumentException if the number is negative
     * @throws NullPointerException if a required value is null
     */
    public Config {
        if (magicNumber < 0) throw new IllegalArgumentException("magicNumber must be nonnegative");
        Objects.requireNonNull(magicNumberIntroduction, "magicNumberIntroduction");
        items = List.copyOf(items);
    }

    /**
     * Reads Fabric's JSON configuration, creating the default file when absent.
     * @param path configuration file path
     * @return validated, non-null configuration
     * @throws IOException if reading, writing or parsing the configuration fails
     */
    public static Config load(Path path) throws IOException {
        if (!Files.exists(path)) {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Files.writeString(path, GSON.toJson(DEFAULT), StandardCharsets.UTF_8);
            return DEFAULT;
        }
        try {
            JsonObject json = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) throw new IOException("Configuration must contain a JSON object: " + path);
            List<String> items = new ArrayList<>(DEFAULT.items());
            if (json.has("items")) {
                items.clear();
                json.getAsJsonArray("items").forEach(item -> items.add(item.getAsString()));
            }
            return new Config(json.has("logDirtBlock") ? json.get("logDirtBlock").getAsBoolean() : DEFAULT.logDirtBlock(),
                json.has("magicNumber") ? json.get("magicNumber").getAsInt() : DEFAULT.magicNumber(),
                json.has("magicNumberIntroduction") ? json.get("magicNumberIntroduction").getAsString() : DEFAULT.magicNumberIntroduction(), items);
        } catch (RuntimeException e) {
            throw new IOException("Invalid configuration: " + path, e);
        }
    }
}
