package dev.sixik.sdmal.core.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.repository.*;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Backend-independent data, lifecycle, cache failure, and native NBT contracts. */
class RepositoryContractsTest {
    /** Temporary collection root, owned by this test. */
    @TempDir Path directory;

    /** Definition used by cache failure fixtures and file repositories. */
    private static final RepositoryDefinition<String, Value> VALUES =
            new RepositoryDefinition<>(
                    s -> s, s -> s, Value::id, RepositoryCodec.gson(Value.class));

    /** Native ECS snapshots survive transfer between NBT files, JSON files, and SQL. */
    @Test
    void nbtJsonSql_nativePayloads_preserveExactTypesAndArrayOwnership() {
        var definition =
                new RepositoryDefinition<String, CompoundTag>(
                        s -> s, s -> s, tag -> "archive", RepositoryCodec.nbt());
        CompoundTag tag = new CompoundTag();
        tag.putLong("large", Long.MAX_VALUE);
        tag.putByte("small", (byte) 5);
        tag.putIntArray("array", new int[] {1, 2, 3});
        try (var nbt = Repositories.nbt(directory.resolve("nbt"));
                var json = Repositories.json(directory.resolve("json"));
                var sql =
                        Repositories.h2(
                                "jdbc:h2:file:" + directory.resolve("sql").toAbsolutePath())) {
            var first = nbt.open("archives", definition);
            var second = json.open("archives", definition);
            var third = sql.open("archives", definition);
            first.upsert("archive", tag);
            second.upsertAll(first.findAll());
            third.upsertAll(second.findAll());
            assertEquals(tag, third.find("archive").orElseThrow());
            CompoundTag copy = first.find("archive").orElseThrow();
            copy.putInt("different", 1);
            assertEquals(tag, first.find("archive").orElseThrow());
        }
    }

    /** Gson values work in compressed NBT files even without a custom native NBT codec. */
    @Test
    void nbtFiles_jsonCodec_roundTripsValues() {
        try (var manager = Repositories.nbt(directory)) {
            var repository = manager.open("records", VALUES);
            repository.upsert("one", new Value("one", 7));
            assertEquals(Map.of("one", new Value("one", 7)), repository.findAll());
        }
    }

    /** Failed reads and writes preserve the previous cache instead of pretending success. */
    @Test
    void cache_backendFailure_preservesCommittedCache() {
        var backend = new FakeRepository();
        try (var cache = new RepositoryStorage<>(backend)) {
            cache.save("one", new Value("one", 3));
            backend.fail = true;
            assertThrows(RepositoryException.class, () -> cache.save("one", new Value("one", 9)));
            assertThrows(RepositoryException.class, () -> cache.delete("one"));
            assertThrows(RepositoryException.class, cache::loadAll);
            assertEquals(new Value("one", 3), cache.get("one").orElseThrow());
        }
        assertTrue(backend.closed);
        assertNull(backend.listener);
    }

    /**
     * Both update and delete notifications read current state, preventing a stale delete event from
     * removing a value recreated before the event was consumed.
     */
    @Test
    void cache_changeEvents_reloadLatestStateAndCloseSubscription() {
        var backend = new FakeRepository();
        var cache = new RepositoryStorage<>(backend, true);
        backend.value = new Value("one", 8);
        backend.listener.accept(new RepositoryChange<>(RepositoryChange.Kind.UPSERT, "one"));
        assertEquals(backend.value, cache.get("one").orElseThrow());
        backend.value = new Value("one", 11);
        backend.listener.accept(new RepositoryChange<>(RepositoryChange.Kind.DELETE, "one"));
        assertEquals(backend.value, cache.get("one").orElseThrow());
        backend.onFailure.accept(new IllegalStateException("broken stream"));
        assertEquals("broken stream", cache.syncFailure().orElseThrow().getMessage());
        cache.close();
        cache.close();
        assertTrue(backend.closed);
        assertNull(backend.listener);
        assertThrows(IllegalStateException.class, () -> cache.get("one"));
    }

    /**
     * Missing entries and corrupt entries remain distinct; malformed data is never silently
     * skipped.
     */
    @Test
    void files_corruptData_throwsWithoutDroppingOtherRecords() throws Exception {
        try (var manager = Repositories.json(directory)) {
            var repository = manager.open("shops", VALUES);
            assertTrue(repository.find("missing").isEmpty());
            repository.upsert("one", new Value("one", 1));
            Path file;
            try (var files = Files.list(directory.resolve("shops"))) {
                file = files.findFirst().orElseThrow();
            }
            Files.writeString(file, "{broken");
            assertThrows(RepositoryException.class, () -> repository.find("one"));
            assertThrows(RepositoryException.class, repository::findAll);
        }
    }

    /** Custom factory lookup is strict; reversible key definitions reject ambiguous conversions. */
    @Test
    void registry_duplicatesUnknownIdsAndBadKeys_areRejected() {
        var registry = new RepositoryManagerRegistry();
        registry.register(" FILE ", () -> Repositories.json(directory));
        assertThrows(IllegalStateException.class, () -> registry.register("file", () -> null));
        try (var manager = registry.create("File")) {
            assertNotNull(manager.open("values", VALUES));
        }
        assertTrue(registry.unregister("file"));
        assertThrows(IllegalArgumentException.class, () -> registry.create("file"));
        var unstable =
                new RepositoryDefinition<String, Value>(
                        String::toLowerCase, s -> s, Value::id, RepositoryCodec.gson(Value.class));
        assertThrows(IllegalArgumentException.class, () -> unstable.decodeKey("UPPER"));
    }

    /**
     * @param id key @param amount example scalar
     */
    private record Value(String id, int amount) {}

    /** Controlled backend for cache ordering and stream lifecycle assertions. */
    private static final class FakeRepository implements ChangeAwareRepository<String, Value> {
        /** Stored single value, or null when deleted. */
        private Value value;

        /** Injected backend failure switch. */
        private boolean fail;

        /** Whether close was called. */
        private boolean closed;

        /** Registered cache callback, or null after subscription close. */
        private Consumer<RepositoryChange<String>> listener;

        /** Registered terminal error callback. */
        private Consumer<Throwable> onFailure;

        /** {@inheritDoc} */
        @Override
        public Optional<Value> find(String key) {
            check();
            return Optional.ofNullable(value);
        }

        /** {@inheritDoc} */
        @Override
        public Map<String, Value> findAll() {
            check();
            return value == null ? Map.of() : Map.of(value.id(), value);
        }

        /** {@inheritDoc} */
        @Override
        public void upsert(String key, Value value) {
            check();
            this.value = value;
        }

        /** {@inheritDoc} */
        @Override
        public boolean delete(String key) {
            check();
            value = null;
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            closed = true;
        }

        /** {@inheritDoc} */
        @Override
        public RepositorySubscription subscribe(
                Consumer<RepositoryChange<String>> listener, Consumer<Throwable> failure) {
            this.listener = listener;
            onFailure = failure;
            return () -> this.listener = null;
        }

        /**
         * @throws RepositoryException when failure injection is enabled
         */
        private void check() {
            if (fail) throw new RepositoryException("injected");
        }
    }
}
