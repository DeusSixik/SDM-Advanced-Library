package dev.sixik.sdmal.impl.ecs;

import dev.sixik.sdmal.core.ecs.FieldedWorldObjectComponent;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentType;
import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.WorldObjectPersistenceException;
import dev.sixik.sdmal.core.ecs.sync.Field;
import dev.sixik.sdmal.core.ecs.sync.FieldCodecs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real compressed-NBT files, backup recovery and refusal to overwrite unreadable/future data. */
class WorldObjectFileStorageTest {
    /** Isolated save directory; never uses a real Minecraft world. */
    @TempDir Path directory;
    /** Registered component type owned by this fixture. */
    private WorldObjectComponentType<Value> type;

    /** Initializes vanilla codec registries once. */
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    /** Registers one isolated component descriptor. */
    @BeforeEach void register() {
        type = WorldObjectComponentRegistry.instance().register(new WorldObjectComponentType<>("test:" + UUID.randomUUID(),
            Value.class, WorldObjectComponentType.DuplicatePolicy.REJECT, Value::new));
    }
    /** Clears the global fixture registration. */
    @AfterEach void unregister() { WorldObjectComponentRegistry.instance().unregister(type.id()); }

    /**
     * An initial absent file becomes a loadable archive with stable UUID and component state.
     * @throws IOException if temporary file IO fails
     */
    @Test void save_newWorld_roundTripsRealCompressedNbt() throws IOException {
        Path file = directory.resolve("nested/world.dat");
        UUID id;
        try (var source = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file);
            assertTrue(storage.load(source).isEmpty());
            var object = source.create(); id = object.id();
            object.attach(new Value(), type).value.set(42);
            storage.save(source);
            assertTrue(Files.isRegularFile(file));
        }
        try (var restored = new WorldObjectManager()) {
            assertTrue(new WorldObjectFileStorage(file).load(restored).isEmpty());
            assertEquals(42, restored.get(id).orElseThrow().getSingle(type).orElseThrow().value.get());
        }
    }

    /**
     * Recovery keeps a valid backup even when the primary was corrupted before a restart.
     * @throws IOException if fixture file IO fails
     */
    @Test void load_corruptPrimary_recoversBackupWithoutOverwritingItWithCorruption() throws IOException {
        Path file = directory.resolve("world.dat");
        Path backup = directory.resolve("world.dat.bak");
        UUID id;
        try (var source = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file); storage.load(source);
            var object = source.create(); id = object.id();
            Value value = object.attach(new Value(), type);
            value.value.set(1); storage.save(source);
            value.value.set(2); storage.save(source);
        }
        byte[] previous = Files.readAllBytes(backup);
        Files.writeString(file, "truncated archive");
        try (var restored = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file);
            assertFalse(storage.load(restored).isEmpty());
            Value value = restored.get(id).orElseThrow().getSingle(type).orElseThrow();
            assertEquals(1, value.value.get());
            value.value.set(3); storage.save(restored);
            assertArrayEquals(previous, Files.readAllBytes(backup));
        }
        try (var restored = new WorldObjectManager()) {
            new WorldObjectFileStorage(file).load(restored);
            assertEquals(3, restored.get(id).orElseThrow().getSingle(type).orElseThrow().value.get());
        }
    }

    /**
     * An unreadable archive disables saving for the whole session.
     * @throws IOException if fixture file IO fails
     */
    @Test void load_corruptWithoutBackup_disablesReplacement() throws IOException {
        Path file = directory.resolve("world.dat");
        byte[] broken = {1, 2, 3, 4}; Files.write(file, broken);
        try (var manager = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file);
            assertThrows(IOException.class, () -> storage.load(manager));
            assertFalse(storage.isWritable());
            manager.create().attach(new Value(), type);
            assertThrows(IOException.class, () -> storage.save(manager));
            assertArrayEquals(broken, Files.readAllBytes(file));
        }
    }

    /**
     * A future primary is not silently replaced with an older, otherwise valid backup.
     * @throws IOException if fixture file IO fails
     */
    @Test void load_futurePrimary_doesNotRollBackToOlderBackup() throws IOException {
        Path file = directory.resolve("world.dat");
        try (var source = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file); storage.load(source);
            source.create().attach(new Value(), type);
            storage.save(source); storage.save(source);
            var future = source.writeNbt(); future.putInt("format", WorldObjectManager.ARCHIVE_FORMAT + 1);
            try (var output = Files.newOutputStream(file)) { NbtIo.writeCompressed(future, output); }
        }
        byte[] original = Files.readAllBytes(file);
        try (var restored = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file);
            assertThrows(IOException.class, () -> storage.load(restored));
            assertFalse(storage.isWritable());
            assertEquals(0, restored.size());
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    /**
     * A failed component snapshot leaves the previous archive untouched.
     * @throws IOException if fixture file IO fails
     */
    @Test void save_untypedComponent_keepsPreviousArchive() throws IOException {
        Path file = directory.resolve("world.dat");
        try (var source = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file); storage.load(source);
            var object = source.create(); object.attach(new Value(), type);
            storage.save(source);
            byte[] before = Files.readAllBytes(file);
            object.attach(new Value());
            assertThrows(WorldObjectPersistenceException.class, () -> storage.save(source));
            assertArrayEquals(before, Files.readAllBytes(file));
            try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
        }
    }

    /**
     * Corruption introduced after load cannot replace a valid previous backup.
     * @throws IOException if fixture file IO fails
     */
    @Test void save_primaryCorruptedDuringSession_preservesBackup() throws IOException {
        Path file = directory.resolve("world.dat");
        try (var source = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file); storage.load(source);
            source.create().attach(new Value(), type);
            storage.save(source); storage.save(source);
            byte[] backup = Files.readAllBytes(directory.resolve("world.dat.bak"));
            Files.writeString(file, "bad data");
            assertThrows(IOException.class, () -> storage.save(source));
            assertEquals("bad data", Files.readString(file));
            assertArrayEquals(backup, Files.readAllBytes(directory.resolve("world.dat.bak")));
            try (var entries = Files.list(directory)) { assertEquals(2, entries.count()); }
        }
    }

    /**
     * A directory is never treated as an absent save file or moved over by an archive.
     * @throws IOException if fixture directory IO fails
     */
    @Test void load_directoryAtArchivePath_refusesAccess() throws IOException {
        Path file = Files.createDirectory(directory.resolve("world.dat"));
        try (var source = new WorldObjectManager()) {
            var storage = new WorldObjectFileStorage(file);
            assertThrows(IOException.class, () -> storage.load(source));
            assertFalse(storage.isWritable());
            assertTrue(Files.isDirectory(file));
        }
    }

    /** Valid dimensions get distinct files, while traversal aliases are refused. */
    @Test void paths_dimensionsStayInsideWorldDirectory() {
        Path first = WorldObjectFileStorage.pathFor(directory, "minecraft", "overworld");
        Path second = WorldObjectFileStorage.pathFor(directory, "minecraft", "the_nether");
        Path nested = WorldObjectFileStorage.pathFor(directory, "example", "planet/moon");
        assertNotEquals(first, second);
        assertTrue(nested.startsWith(directory));
        assertThrows(IllegalArgumentException.class, () -> WorldObjectFileStorage.pathFor(directory, "..", "overworld"));
        assertThrows(IllegalArgumentException.class, () -> WorldObjectFileStorage.pathFor(directory, "example", "../escape"));
        assertThrows(IllegalArgumentException.class, () -> WorldObjectFileStorage.pathFor(directory, "example", "planet//moon"));
    }

    /** Persistent field fixture, recreated through a registered factory. */
    private static final class Value extends FieldedWorldObjectComponent {
        /** Saved integer payload. */
        final Field<Integer> value = field("value", FieldCodecs.intValue(), 0);
    }
}
