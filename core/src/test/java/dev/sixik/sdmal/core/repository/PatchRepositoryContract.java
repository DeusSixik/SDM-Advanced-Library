package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Identical behavioral and concurrency checks run against real H2 and real MongoDB.
 */
abstract class PatchRepositoryContract {
    /**
     * Shared codec for immutable application data, including nested values and nullable fields.
     */
    static final RepositoryDefinition<String, Value> DEFINITION = definition(RepositoryCodec.gson(Value.class));

    /**
     * Opens a manager in an isolated disposable backend.
     *
     * @return manager whose namespace belongs exclusively to this test
     */
    abstract RepositoryManager manager();

    /**
     * Verifies CRUD, full replacements, separate partitions and handle lifetime.
     */
    @Test
    void fieldStore_crudAndPartitions_preserveRepositoryContract() {
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            assertTrue(repository.findVersioned("one").isEmpty());
            repository.upsert("one", initial());
            assertEquals(initial(), repository.find("one").orElseThrow());
            assertEquals(Map.of("one", initial()), repository.findAll());
            assertTrue(manager.openPatchable("orders", DEFINITION).findAll().isEmpty());
            assertTrue(manager.open("shops", DEFINITION).findAll().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repository.upsert("wrong", initial()));
            assertTrue(repository.delete("one"));
            assertFalse(repository.delete("one"));
            assertTrue(repository.findAll().isEmpty());
            repository.close();
            assertThrows(IllegalStateException.class, () -> repository.find("one"));
            assertThrows(IllegalStateException.class, () -> repository.patch("one", UUID.randomUUID(), p -> p.set("amount", 1)));
        }
    }

    /**
     * Multiple field documents retain their own revisions and values after reopening handles.
     */
    @Test
    void fieldStore_reopenAndFindAll_preservesIndependentDocuments() {
        RepositorySnapshot<Value> original;
        var second = new Value("two", 27, "other", List.of(), new Details(false, 6));
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            repository.upsert("one", initial());
            repository.upsert("two", second);
            original = repository.findVersioned("one").orElseThrow();
        }
        try (var reopened = manager()) {
            var repository = reopened.openPatchable("shops", DEFINITION);
            assertEquals(original, repository.findVersioned("one").orElseThrow());
            assertEquals(Map.of("one", initial(), "two", second), repository.findAll());
            repository.patch("two", repository.findVersioned("two").orElseThrow().revision(), p -> p.set("amount", 30));
            assertEquals(original, repository.findVersioned("one").orElseThrow());
        }
    }

    /**
     * Ensures fields are decoded identically and unrelated values survive successive patches.
     */
    @Test
    void patch_selectedFields_preservesOthersAndReturnsNewRevision() {
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            repository.upsert("one", initial());
            var before = repository.findVersioned("one").orElseThrow();
            var after = repository.patch("one", before.revision(), p -> p.set("amount", Long.MAX_VALUE));
            assertNotEquals(before.revision(), after.revision());
            assertEquals(Long.MAX_VALUE, after.value().amount());
            assertEquals(initial().note(), after.value().note());
            assertEquals(after, repository.findVersioned("one").orElseThrow());
            var next = repository.patch("one", after.revision(), p -> p.set("note", "renamed"));
            assertEquals(Long.MAX_VALUE, next.value().amount());
            assertEquals("renamed", next.value().note());
        }
    }

    /**
     * Covers JSON null, removals, compound replacements and last-operation-wins semantics.
     */
    @Test
    void patch_nullUnsetAndNestedValues_roundTrip() {
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            repository.upsert("one", initial());
            var before = repository.findVersioned("one").orElseThrow();
            var after = repository.patch("one", before.revision(), p -> p
                    .set("note", "discarded").unset("note").set("note", null)
                    .set("tags", new String[] {"x", "y"})
                    .setJson("details", "{\"enabled\":false,\"limit\":9223372036854775807}")
                    .unset("absent"));
            assertNull(after.value().note());
            assertEquals(List.of("x", "y"), after.value().tags());
            assertEquals(new Details(false, Long.MAX_VALUE), after.value().details());
            var removed = repository.patch("one", after.revision(), p -> p.unset("amount"));
            assertEquals(0, removed.value().amount());
            assertEquals(removed, repository.findVersioned("one").orElseThrow());
        }
    }

    /**
     * Invalid identity changes and decoder failures must leave both fields and revision intact.
     */
    @Test
    void patch_invalidCandidate_leavesRecordUntouched() {
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            repository.upsert("one", initial());
            var before = repository.findVersioned("one").orElseThrow();
            assertThrows(IllegalArgumentException.class,
                    () -> repository.patch("one", before.revision(), p -> p.set("id", "another")));
            assertThrows(RuntimeException.class,
                    () -> repository.patch("one", before.revision(), p -> p.unset("id")));
            assertThrows(RuntimeException.class,
                    () -> repository.patch("one", before.revision(), p -> p.setJson("details", "[]")));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.patch("one", before.revision(), p -> {}));
            assertEquals(before, repository.findVersioned("one").orElseThrow());
        }
    }

    /**
     * Even an identical unconditional replacement invalidates every previous write token.
     */
    @Test
    void patch_afterFullUpsert_rejectsOldRevision() {
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            repository.upsert("one", initial());
            var old = repository.findVersioned("one").orElseThrow();
            repository.upsert("one", initial());
            assertNotEquals(old.revision(), repository.findVersioned("one").orElseThrow().revision());
            assertThrows(RepositoryConflictException.class,
                    () -> repository.patch("one", old.revision(), p -> p.set("amount", 42)));
            assertEquals(initial(), repository.find("one").orElseThrow());
        }
    }

    /**
     * Prevents deletion/recreation ABA and prohibits implicit insertion through patch.
     */
    @Test
    void patch_missingOrRecreatedRecord_rejectsOldRevision() {
        try (var manager = manager()) {
            var repository = manager.openPatchable("shops", DEFINITION);
            assertThrows(RepositoryConflictException.class,
                    () -> repository.patch("one", UUID.randomUUID(), p -> p.set("amount", 3)));
            repository.upsert("one", initial());
            var old = repository.findVersioned("one").orElseThrow();
            repository.delete("one");
            assertThrows(RepositoryConflictException.class,
                    () -> repository.patch("one", old.revision(), p -> p.set("amount", 3)));
            repository.upsert("one", initial());
            assertThrows(RepositoryConflictException.class,
                    () -> repository.patch("one", old.revision(), p -> p.set("amount", 3)));
        }
    }

    /**
     * Synchronizes candidate decoding so both independent handles reach CAS from the same revision.
     */
    @Test
    void patch_twoWritersWithSameRevision_commitsExactlyOneWholeChange() throws Exception {
        AtomicBoolean racing = new AtomicBoolean();
        CountDownLatch decoded = new CountDownLatch(2);
        RepositoryCodec<Value> delegate = RepositoryCodec.gson(Value.class);
        var definition = definition(new RepositoryCodec<>() {
            @Override
            public String encode(Value value) {
                return delegate.encode(value);
            }

            @Override
            public Value decode(String json) {
                Value value = delegate.decode(json);
                if (!racing.get()) {
                    return value;
                }
                decoded.countDown();
                try {
                    if (!decoded.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Both candidates must reach compare-and-set");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return value;
            }
        });
        var executor = Executors.newFixedThreadPool(2);
        try (var manager = manager(); var second = manager()) {
            var firstRepository = manager.openPatchable("shops", definition);
            var secondRepository = second.openPatchable("shops", definition);
            firstRepository.upsert("one", initial());
            var before = firstRepository.findVersioned("one").orElseThrow();
            racing.set(true);
            var first = executor.submit(() -> attempt(firstRepository, before.revision(), 1, "first"));
            var other = executor.submit(() -> attempt(secondRepository, before.revision(), 2, "second"));
            assertEquals(1, (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (other.get(15, TimeUnit.SECONDS) ? 1 : 0));
            racing.set(false);
            var result = firstRepository.find("one").orElseThrow();
            assertTrue((result.amount() == 1 && result.note().equals("first"))
                    || (result.amount() == 2 && result.note().equals("second")));
            assertEquals(initial().details(), result.details());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Ordinary JSON records are migrated explicitly; opening field storage does not hide a rewrite.
     */
    @Test
    void ordinaryRepository_explicitCopy_migratesWithoutChangingSource() {
        try (var manager = manager()) {
            var source = manager.open("shops", DEFINITION);
            var destination = manager.openPatchable("shops", DEFINITION);
            source.upsert("one", initial());
            assertTrue(destination.find("one").isEmpty());
            assertEquals(1, Repositories.copy(source, destination));
            var snapshot = destination.findVersioned("one").orElseThrow();
            destination.patch("one", snapshot.revision(), p -> p.set("amount", 100));
            assertEquals(initial(), source.find("one").orElseThrow());
            assertEquals(100, destination.find("one").orElseThrow().amount());
        }
    }

    /**
     * Tests exact field JSON numbers beyond IEEE double precision using a custom raw JSON codec.
     */
    @Test
    void customCodec_largeNumbersAndUnknownFields_survivePatch() {
        var definition = new RepositoryDefinition<String, String>(s -> s, s -> s, s -> "one",
                new RepositoryCodec<>() {
                    @Override
                    public String encode(String value) { return value; }
                    @Override
                    public String decode(String json) { return json; }
                });
        try (var manager = manager()) {
            var repository = manager.openPatchable("raw", definition);
            repository.upsert("one", "{\"huge\":123456789012345678901234567890,\"note\":null,\"extra\":{\"x\":7}}");
            var before = repository.findVersioned("one").orElseThrow();
            var after = repository.patch("one", before.revision(), p -> p.set("price", 12));
            Map<String, String> fields = RepositoryFields.split(repository.find("one").orElseThrow());
            assertEquals("123456789012345678901234567890", fields.get("huge"));
            assertEquals("null", fields.get("note"));
            assertEquals("{\"x\":7}", fields.get("extra"));
            assertEquals("12", fields.get("price"));
            assertEquals(after.revision(), repository.findVersioned("one").orElseThrow().revision());
        }
    }

    /**
     * Field storage rejects opaque scalar/base64 codecs without persisting a partial record.
     */
    @Test
    void scalarCodec_upsert_rejectsOpaquePayload() {
        var scalar = new RepositoryDefinition<String, String>(s -> s, s -> s, s -> s,
                RepositoryCodec.gson(String.class));
        try (var manager = manager()) {
            var repository = manager.openPatchable("opaque", scalar);
            assertThrows(IllegalArgumentException.class, () -> repository.upsert("one", "one"));
            assertTrue(repository.findAll().isEmpty());
        }
    }

    /**
     * Handles cannot outlive their connection-owning manager.
     */
    @Test
    void managerClosed_allFieldOperations_rejectFurtherUse() {
        var manager = manager();
        var repository = manager.openPatchable("shops", DEFINITION);
        manager.close();
        assertThrows(IllegalStateException.class, () -> repository.findVersioned("one"));
        assertThrows(IllegalStateException.class, repository::findAll);
        assertThrows(IllegalStateException.class, () -> repository.upsert("one", initial()));
        assertThrows(IllegalStateException.class, () -> repository.delete("one"));
    }

    /**
     * Executes one competing conditional edit.
     *
     * @param repository writer handle
     * @param revision shared original token
     * @param amount writer-specific amount
     * @param note writer-specific second field
     * @return true for success or false for a revision conflict
     */
    private static boolean attempt(PatchableRepository<String, Value> repository, UUID revision, long amount, String note) {
        try {
            repository.patch("one", revision, p -> p.set("amount", amount).set("note", note));
            return true;
        } catch (RepositoryConflictException conflict) {
            return false;
        }
    }

    /**
     * @param codec application JSON-object codec
     * @return fixture identity mapping
     */
    private static RepositoryDefinition<String, Value> definition(RepositoryCodec<Value> codec) {
        return new RepositoryDefinition<>(s -> s, s -> s, Value::id, codec);
    }

    /**
     * @return immutable initial record used by every backend
     */
    static Value initial() {
        return new Value("one", 9, "original", List.of("a", "b"), new Details(true, 32));
    }

    /**
     * @param id persisted identity
     * @param amount numeric data field
     * @param note nullable application label
     * @param tags whole-array replacement example
     * @param details nested-object replacement example
     */
    record Value(String id, long amount, String note, List<String> tags, Details details) {}

    /**
     * @param enabled nested flag
     * @param limit exact signed 64-bit value
     */
    record Details(boolean enabled, long limit) {}
}
