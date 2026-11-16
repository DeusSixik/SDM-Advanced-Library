package dev.sixik.sdmal.impl.ecs;

import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.WorldObjectPersistenceException;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Owner-thread file session with bounded compressed-NBT reads, backup recovery and guarded replacement. */
public final class WorldObjectFileStorage {
    /** Maximum NBT allocation budget accepted from one saved world, in bytes. */
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    /** Absolute normalized primary archive path. */
    private final Path file;
    /** Last known previous primary archive, in the same directory. */
    private final Path backup;
    /** Whether this session has already attempted loading. */
    private boolean loaded;
    /** Whether a successful load authorized subsequent replacement. */
    private boolean writable;
    /** Prevents a corrupt primary from replacing the valid backup used during recovery. */
    private boolean recoveredBackup;

    /**
     * Opens a file session without touching the filesystem.
     * @param file archive path; caller selects a world-specific path
     */
    public WorldObjectFileStorage(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        backup = this.file.resolveSibling(this.file.getFileName() + ".bak");
    }

    /**
     * Resolves one namespace/dimension path underneath the world's ECS directory.
     * @param worldRoot saved world's root folder, non-null
     * @param namespace dimension namespace containing lowercase identifier characters
     * @param dimension slash-separated identifier path without traversal/empty segments
     * @return normalized archive path under worldRoot/data/sdmal/ecs
     * @throws IllegalArgumentException if either identifier part could alias or escape its directory
     */
    public static Path pathFor(Path worldRoot, String namespace, String dimension) {
        if (!namespace.matches("[a-z0-9_.-]+") || namespace.equals(".") || namespace.equals("..")
            || !dimension.matches("[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid dimension path");
        for (String part : dimension.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid dimension path segment");
        Path base = worldRoot.toAbsolutePath().normalize().resolve("data/sdmal/ecs");
        Path result = base.resolve(namespace).resolve(dimension + ".dat").normalize();
        if (!result.startsWith(base)) throw new IllegalArgumentException("Dimension archive escaped world directory");
        return result;
    }

    /** @return true after a successful load, including an absent initial archive */
    public boolean isWritable() { return writable; }

    /**
     * Loads a fresh manager, recovering a previous backup only for corrupt/missing primary data.
     * Unsupported future archive formats block this session rather than rolling data backward.
     * @param manager fresh owner-thread manager
     * @return immutable load diagnostics, including backup recovery and unresolved objects
     * @throws IOException if neither archive can be loaded safely; saving stays disabled
     * @throws IllegalStateException if called twice, on the wrong thread or with a used manager
     */
    public List<String> load(WorldObjectManager manager) throws IOException {
        manager.checkThread();
        if (loaded) throw new IllegalStateException("Archive session has already attempted loading");
        loaded = true;
        IOException primaryFailure = null;
        if (exists(file)) {
            try {
                List<String> warnings = restore(file, manager);
                writable = true;
                return warnings;
            } catch (WorldObjectPersistenceException.FutureFormat future) {
                throw new IOException("Newer ECS archive left untouched: " + file, future);
            } catch (WorldObjectPersistenceException | IOException failure) {
                primaryFailure = new IOException("Could not load ECS archive " + file, failure);
            }
        }
        if (exists(backup)) {
            try {
                List<String> warnings = new ArrayList<>(restore(backup, manager));
                warnings.add("Recovered previous ECS archive from " + backup);
                recoveredBackup = true;
                writable = true;
                return List.copyOf(warnings);
            } catch (WorldObjectPersistenceException | IOException failure) {
                IOException error = new IOException("Could not load ECS backup " + backup, failure);
                if (primaryFailure != null) error.addSuppressed(primaryFailure);
                throw error;
            }
        }
        if (primaryFailure != null) throw primaryFailure;
        CompoundTag empty = new CompoundTag();
        empty.putInt("format", WorldObjectManager.ARCHIVE_FORMAT);
        empty.putLong("tick", 0);
        empty.put("objects", new ListTag());
        List<String> warnings = manager.readNbt(empty);
        writable = true;
        return warnings;
    }

    /**
     * Captures and validates a temporary archive before replacing the primary file.
     * A valid previous primary is copied to .bak before replacement. Writes are
     * forced to the file; atomic rename is used where the filesystem supports it.
     * @param manager live owner-thread manager
     * @throws IOException if loading failed/not attempted, serialization exceeds limits or disk IO fails
     * @throws WorldObjectPersistenceException if a durable component cannot be serialized safely
     */
    public void save(WorldObjectManager manager) throws IOException {
        manager.checkThread();
        if (!writable) throw new IOException("Saving disabled because ECS archive was not loaded safely: " + file);
        CompoundTag snapshot = manager.writeNbt();
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "ecs-", ".tmp");
        Path backupTemporary = null;
        try {
            try (var output = Files.newOutputStream(temporary)) { NbtIo.writeCompressed(snapshot, output); }
            force(temporary);
            readTag(temporary);
            if (exists(file) && !recoveredBackup) {
                readTag(file);
                backupTemporary = Files.createTempFile(file.getParent(), "ecs-backup-", ".tmp");
                Files.copy(file, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                force(backupTemporary);
                replace(backupTemporary, backup);
                backupTemporary = null;
            }
            replace(temporary, file);
            recoveredBackup = false;
        } finally {
            Files.deleteIfExists(temporary);
            if (backupTemporary != null) Files.deleteIfExists(backupTemporary);
        }
    }

    /**
     * Reads and restores one candidate without mutating the file.
     * @param source primary or backup archive path
     * @param manager fresh owner-thread manager
     * @return immutable restore diagnostics
     * @throws IOException for malformed compressed NBT
     * @throws WorldObjectPersistenceException for an invalid archive envelope
     */
    private static List<String> restore(Path source, WorldObjectManager manager) throws IOException {
        return manager.readNbt(readTag(source));
    }

    /**
     * Distinguishes a missing archive from denied access or a directory masquerading as a save file.
     * @param path primary or backup path
     * @return true only for an existing regular file
     * @throws IOException for inaccessible or non-file paths
     */
    private static boolean exists(Path path) throws IOException {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile())
                throw new IOException("ECS archive path is not a regular file: " + path);
            return true;
        } catch (NoSuchFileException missing) { return false; }
    }

    /**
     * Reads a bounded compressed compound through the version's NBT allocation accountant.
     * @param source existing archive file
     * @return independent parsed root tag
     * @throws IOException if the compressed file or decoded allocation budget is invalid
     */
    private static CompoundTag readTag(Path source) throws IOException {
        if (Files.size(source) > MAX_NBT_BYTES) throw new IOException("Compressed ECS archive is too large");
        //? if >=1.20.3 {
        /*NbtAccounter accounter = NbtAccounter.create(MAX_NBT_BYTES);
        *///?} else {
        NbtAccounter accounter = new NbtAccounter(MAX_NBT_BYTES);
        //?}
        try (var raw = Files.newInputStream(source);
             var gzip = new GZIPInputStream(raw);
             var input = new DataInputStream(gzip)) {
            return NbtIo.read(input, accounter);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid ECS NBT archive: " + source, failure);
        }
    }

    /**
     * Flushes temporary file contents before replacement.
     * @param path existing writable temporary file
     * @throws IOException if the force operation fails
     */
    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    /**
     * Replaces a file within the same directory, preferring an atomic rename.
     * @param source validated temporary file
     * @param target destination primary or backup file
     * @throws IOException if replacement fails
     */
    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
