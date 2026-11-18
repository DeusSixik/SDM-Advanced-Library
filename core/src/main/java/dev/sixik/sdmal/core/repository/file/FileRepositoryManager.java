package dev.sixik.sdmal.core.repository.file;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sixik.sdmal.api.repository.NbtRepositoryCodec;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.api.repository.RepositoryManager;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;
import dev.sixik.sdmal.core.repository.NbtDocuments;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stores entries as JSON or compressed NBT; each replacement uses a unique temporary file.
 */
@ApiStatus.Internal
public final class FileRepositoryManager implements RepositoryManager {
    /**
     * Absolute normalized root of all named collections.
     */
    private final Path root;

    /**
     * Selects compressed NBT envelopes instead of JSON files.
     */
    private final boolean nbt;

    /**
     * Permanent closed state; further operations are rejected.
     */
    private boolean closed;

    /**
     * Creates a file-backed manager without touching disk until a repository is opened.
     *
     * @param root directory containing named repositories
     */
    public FileRepositoryManager(Path root) {
        this(root, false);
    }

    /**
     * @param root collection root
     * @param nbt  true for native NBT envelopes
     */
    public FileRepositoryManager(Path root, boolean nbt) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.nbt = nbt;
    }

    /**
     * Opens a named file repository.
     *
     * @param name       safe repository name
     * @param definition stable key and value codecs
     * @param <K>        key type
     * @param <V>        value type
     * @return file-backed repository
     */
    @Override
    public synchronized <K, V> Repository<K, V> open(
            String name, RepositoryDefinition<K, V> definition) {
        requireOpen();
        Objects.requireNonNull(definition, "definition");
        validateName(name);
        Path directory = root.resolve(name).normalize();
        if (!directory.startsWith(root))
            throw new IllegalArgumentException("Repository escaped root");
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new RepositoryException(
                    "Cannot create repository directory " + directory, failure);
        }
        return new FileRepository<>(this, directory, definition);
    }

    /**
     * Closes the manager and prevents opening new repositories.
     */
    @Override
    public synchronized void close() {
        closed = true;
    }

    /**
     * @throws IllegalStateException if the manager has closed
     */
    private synchronized void requireOpen() {
        if (closed) throw new IllegalStateException("File repository manager is closed");
    }

    /**
     * @param name backend-safe collection name
     * @throws IllegalArgumentException for a blank or unsafe identifier
     */
    private static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9_.-]{1,128}") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Invalid repository name: " + name);
    }

    /**
     * @param key encoded application key
     * @return collision-resistant, filesystem-safe name without an extension
     */
    private static String fileName(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class FileRepository<K, V> implements Repository<K, V> {
        /**
         * Owning backend session; handles cannot outlive it.
         */
        private final FileRepositoryManager manager;

        /**
         * Absolute directory containing this collection only.
         */
        private final Path directory;

        /**
         * Stable application key and value encoding contract.
         */
        private final RepositoryDefinition<K, V> definition;

        /**
         * Permanent closed state; further operations are rejected.
         */
        private boolean closed;

        /**
         * @param manager    owning backend session
         * @param directory  physical collection
         * @param definition application codecs
         */
        private FileRepository(
                FileRepositoryManager manager,
                Path directory,
                RepositoryDefinition<K, V> definition) {
            this.manager = manager;
            this.directory = directory;
            this.definition = definition;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized Optional<V> find(K key) {
            checkOpen();
            String encodedKey = definition.encodeKey(key);
            Path file = entryPath(encodedKey);
            if (Files.notExists(file)) return Optional.empty();
            if (!Files.isRegularFile(file))
                throw new RepositoryException("Repository entry is not a regular file: " + file);
            return Optional.of(decode(file, encodedKey));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized Map<K, V> findAll() {
            checkOpen();
            Map<K, V> result = new Object2ObjectOpenHashMap<>();
            try (DirectoryStream<Path> files =
                         Files.newDirectoryStream(directory, manager.nbt ? "*.nbt" : "*.json")) {
                for (Path file : files) {
                    V value = decode(file, null);
                    K key =
                            Objects.requireNonNull(
                                    definition.keyExtractor().apply(value), "extracted key");
                    String encodedKey = definition.encodeKey(key);
                    if (!file.equals(entryPath(encodedKey)))
                        throw new RepositoryException("Stored key does not match file " + file);
                    if (result.putIfAbsent(key, value) != null)
                        throw new RepositoryException("Duplicate repository key: " + encodedKey);
                }
                return result;
            } catch (IOException failure) {
                throw new RepositoryException(
                        "Cannot read repository directory " + directory, failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void upsert(K key, V value) {
            checkOpen();
            String encodedKey = definition.verifyEntityKey(key, value);
            String json = null;
            if (!manager.nbt)
                try {
                    json = Objects.requireNonNull(definition.codec().encode(value), "encoded JSON");
                    JsonObject record = new JsonObject();
                    record.addProperty("key", encodedKey);
                    record.add("value", JsonParser.parseString(json));
                    json = record.toString();
                } catch (RuntimeException failure) {
                    throw new RepositoryException(
                            "Cannot encode repository value " + encodedKey, failure);
                }
            Path target = entryPath(encodedKey);
            Path temporary = null;
            try {
                temporary = Files.createTempFile(directory, "entry-", ".tmp");
                if (manager.nbt) {
                    CompoundTag record = new CompoundTag();
                    record.putString("key", encodedKey);
                    if (definition.codec() instanceof NbtRepositoryCodec<?> nativeCodec) {
                        @SuppressWarnings("unchecked")
                        var codec = (NbtRepositoryCodec<V>) nativeCodec;
                        record.put(
                                "value",
                                Objects.requireNonNull(codec.writeNbt(value), "NBT payload"));
                    } else record.putString("payload_json", definition.codec().encode(value));
                    try (var output =
                                 new GZIPOutputStream(Files.newOutputStream(temporary))) {
                        output.write(NbtDocuments.encode(record));
                    }
                } else {
                    if (json.getBytes(StandardCharsets.UTF_8).length > NbtDocuments.MAX_BYTES)
                        throw new RepositoryException("File repository record exceeds 16 MiB");
                    Files.writeString(
                            temporary,
                            json,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                replace(temporary, target);
            } catch (IOException | RuntimeException failure) {
                throw new RepositoryException(
                        "Cannot save repository value " + encodedKey, failure);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized boolean delete(K key) {
            checkOpen();
            try {
                return Files.deleteIfExists(entryPath(definition.encodeKey(key)));
            } catch (IOException failure) {
                throw new RepositoryException("Cannot delete repository value " + key, failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void close() {
            closed = true;
        }

        /**
         * @param file        stored record
         * @param expectedKey requested key, or null during enumeration
         * @return decoded value with matching identity
         */
        private V decode(Path file, String expectedKey) {
            try {
                String storedKey;
                V value;
                if (Files.size(file) > NbtDocuments.MAX_BYTES)
                    throw new RepositoryException("Repository record exceeds 16 MiB");
                if (manager.nbt) {
                    byte[] bytes;
                    try (var input =
                                 new GZIPInputStream(Files.newInputStream(file))) {
                        bytes = input.readNBytes(NbtDocuments.MAX_BYTES + 1);
                    }
                    CompoundTag record = NbtDocuments.decode(bytes);
                    storedKey = NbtAccess.string(record.get("key"), "");
                    if (definition.codec() instanceof NbtRepositoryCodec<?> nativeCodec) {
                        if (!(record.get("value") instanceof CompoundTag payload))
                            throw new RepositoryException("Missing native NBT value");
                        @SuppressWarnings("unchecked")
                        var codec = (NbtRepositoryCodec<V>) nativeCodec;
                        value = codec.readNbt(payload);
                    } else
                        value =
                                definition
                                        .codec()
                                        .decode(NbtAccess.string(record.get("payload_json"), ""));
                } else {
                    JsonObject record =
                            JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    storedKey = record.get("key").getAsString();
                    value = definition.codec().decode(record.get("value").toString());
                }
                if (expectedKey != null && !expectedKey.equals(storedKey))
                    throw new RepositoryException(
                            "Stored key does not match requested key: " + file);
                Objects.requireNonNull(value, "decoded value");
                String entityKey =
                        definition.verifyEntityKey(
                                Objects.requireNonNull(
                                        definition.keyExtractor().apply(value), "extracted key"),
                                value);
                if (!storedKey.equals(entityKey))
                    throw new RepositoryException(
                            "Stored key does not match entity value: " + file);
                return value;
            } catch (RepositoryException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new RepositoryException("Cannot decode repository value " + file, failure);
            }
        }

        /**
         * @throws IllegalStateException if this handle or its manager has closed
         */
        private void checkOpen() {
            manager.requireOpen();
            if (closed) throw new IllegalStateException("File repository is closed");
        }

        /**
         * @param key encoded repository key
         * @return path confined to the collection
         */
        private Path entryPath(String key) {
            return directory.resolve(fileName(key) + (manager.nbt ? ".nbt" : ".json"));
        }

        /**
         * @param source completed temporary file
         * @param target published record path
         * @throws IOException when replacement fails
         */
        private static void replace(Path source, Path target) throws IOException {
            try {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
