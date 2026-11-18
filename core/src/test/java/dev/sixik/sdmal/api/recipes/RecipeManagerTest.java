package dev.sixik.sdmal.api.recipes;

import dev.sixik.sdmal.core.utils.ObjectArrayView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates coherent publication, deterministic matching, index ownership, and concurrent readers.
 */
class RecipeManagerTest {
    /**
     * Pure linear category used to compare primitive-indexed behavior.
     */
    private static final RecipeType<Integer, Value> LINEAR = RecipeType.of("test:linear", (r, i) -> r.key() == i);
    /**
     * Pure indexed category; duplicate keys deliberately exercise candidate deduplication.
     */
    private static final RecipeType<Integer, Value> INDEXED = RecipeType.indexed("test:indexed",
            (r, i) -> r.key() == i, Integer::intValue, r -> new int[] {r.key(), r.key()});

    /**
     * Empty queries must not construct mutable caches or fail for an absent category.
     */
    @Test
    void emptyManager_lookupAndQuery_returnsEmptyGeneration() {
        var manager = new RecipeManager();
        assertEquals(0, manager.snapshot().generation());
        assertEquals(0, manager.snapshot().size());
        assertNull(manager.snapshot().byId("test:missing"));
        assertNull(manager.snapshot().lookup(LINEAR));
        assertNull(manager.first(LINEAR, 3));
        assertThrows(NullPointerException.class, () -> manager.first(LINEAR, null));
    }

    /**
     * Matching order must be independent of source discovery order and stable across category indices.
     */
    @Test
    void indexedAndLinear_sameData_findIdenticalDeterministicMatches() {
        for (var type : ObjectArrayView.of(LINEAR, INDEXED)) {
            var manager = new RecipeManager();
            manager.replaceAll(ObjectArrayView.of(entry("z", type, 7), entry("a", type, 7), entry("b", type, 8)));
            var snapshot = manager.snapshot();
            var lookup = snapshot.lookup(type);
            assertEquals("test:a", lookup.first(7).id());
            assertEquals("test:b", lookup.first(8).id());
            assertNull(lookup.first(9));
            assertEquals(3, lookup.size());
            var found = new ObjectArrayList<RecipeEntry<Value>>();
            found.add(entry("previous", type, 0));
            assertEquals(2, lookup.findAll(7, found));
            assertEquals(ObjectArrayView.of("test:previous", "test:a", "test:z"), found.stream().map(RecipeEntry::id).toList());
            found.clear();
            assertEquals(2, lookup.forEachMatch(7, found::add));
            assertEquals(ObjectArrayView.of("test:a", "test:z"), found.stream().map(RecipeEntry::id).toList());
            assertSame(snapshot.byId("test:a"), snapshot.first(type, 7));
        }
    }

    /**
     * Every search still runs the full matcher after candidate narrowing.
     */
    @Test
    void indexed_coarseKey_rejectsNonmatchingCandidate() {
        var type = RecipeType.<Integer, Value>indexed("test:coarse", (r, i) -> r.key() == i,
                i -> 0, r -> new int[] {0});
        var manager = new RecipeManager();
        manager.replaceAll(ObjectArrayView.of(entry("first", type, 4), entry("second", type, 5)));
        assertEquals("test:second", manager.first(type, 5).id());
        assertNull(manager.first(type, 6));
    }

    /**
     * An empty candidate key set advertises that no input can match this prepared entry.
     */
    @Test
    void indexed_emptyKeys_neverBecomesCandidate() {
        var type = RecipeType.<Integer, Value>indexed("test:none", (r, i) -> true, i -> i, r -> new int[0]);
        var manager = new RecipeManager();
        manager.replaceAll(ObjectArrayView.of(entry("one", type, 1)));
        assertEquals(1, manager.snapshot().size());
        assertNull(manager.first(type, 1));
    }

    /**
     * Negative primitive keys and duplicate per-recipe keys preserve all valid matches exactly once.
     */
    @Test
    void indexed_negativeAndDuplicateKeys_deduplicatesWithoutLosingMatches() {
        var manager = new RecipeManager();
        manager.replaceAll(ObjectArrayView.of(entry("one", INDEXED, Integer.MIN_VALUE)));
        var matches = new ObjectArrayList<RecipeEntry<Value>>();
        assertEquals(1, manager.snapshot().lookup(INDEXED).findAll(Integer.MIN_VALUE, matches));
        assertEquals(1, matches.size());
    }

    /**
     * Source arrays and collections cannot mutate internal candidate structures after publication.
     */
    @Test
    void rebuild_sourceAndKeyArrayChanged_keepsPublishedIndicesIndependent() {
        int[] keys = {4};
        var type = RecipeType.<Integer, Value>indexed("test:owned", (r, i) -> r.key() == i, i -> i, r -> keys);
        var source = new ObjectArrayList<RecipeEntry<?>>();
        source.add(entry("one", type, 4));
        var manager = new RecipeManager();
        var snapshot = manager.replaceAll(source);
        source.clear();
        keys[0] = 5;
        assertNotNull(snapshot.first(type, 4));
        assertNull(snapshot.first(type, 5));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lookup(type).entries().clear());
        var copied = snapshot.entries().toArray();
        copied[0] = null;
        assertNotNull(snapshot.first(type, 4));
    }

    /**
     * A worker retains coherent old-generation semantics after complete replacement and clear.
     */
    @Test
    void replaceAndClear_oldSnapshot_remainsReadableAndBecomesStale() {
        var manager = new RecipeManager();
        var old = manager.replaceAll(ObjectArrayView.of(entry("old", LINEAR, 1)));
        manager.replaceAll(ObjectArrayView.of(entry("new", LINEAR, 2)));
        assertEquals("test:old", old.first(LINEAR, 1).id());
        assertNull(old.first(LINEAR, 2));
        assertFalse(manager.isCurrent(old));
        assertTrue(manager.isCurrent(manager.snapshot()));
        var current = manager.snapshot();
        manager.clear();
        assertEquals(3, manager.snapshot().generation());
        assertFalse(manager.isCurrent(current));
        assertEquals("test:new", current.first(LINEAR, 2).id());
    }

    /**
     * Generation numbers do not identify snapshots across different manager lifetimes.
     */
    @Test
    void differentManagers_sameGeneration_doNotPassIdentityGuard() {
        var first = new RecipeManager();
        var second = new RecipeManager();
        first.replaceAll(ObjectArrayView.of(entry("one", LINEAR, 1)));
        second.replaceAll(ObjectArrayView.of(entry("one", LINEAR, 1)));
        assertEquals(first.snapshot().generation(), second.snapshot().generation());
        assertFalse(first.isCurrent(second.snapshot()));
    }

    /**
     * Invalid rebuilds must not consume a generation or partially publish an id/category index.
     */
    @Test
    void rebuild_duplicateIdsAndCategoryTokens_retainsPreviousSnapshot() {
        var manager = new RecipeManager();
        var old = manager.replaceAll(ObjectArrayView.of(entry("one", LINEAR, 1)));
        assertThrows(IllegalArgumentException.class, () -> manager.replaceAll(ObjectArrayView.of(
                entry("same", LINEAR, 1), entry("same", INDEXED, 2))));
        var conflicting = RecipeType.<Integer, Value>of("test:linear", (r, i) -> true);
        assertThrows(IllegalArgumentException.class, () -> manager.replaceAll(ObjectArrayView.of(
                entry("one", LINEAR, 1), entry("two", conflicting, 2))));
        assertSame(old, manager.snapshot());
        assertEquals(1, old.generation());
    }

    /**
     * Failed adapter/index logic leaves the old complete generation available.
     */
    @Test
    void rebuild_keyExtractorFailure_doesNotPublish() {
        var manager = new RecipeManager();
        var old = manager.snapshot();
        var type = RecipeType.<Integer, Value>indexed("test:bad", (r, i) -> true, i -> i,
                r -> { throw new IllegalStateException("fixture"); });
        assertThrows(IllegalStateException.class,
                () -> manager.replaceAll(ObjectArrayView.of(entry("one", type, 1))));
        assertSame(old, manager.snapshot());
    }

    /**
     * Invalid ids, entries and callbacks fail at construction rather than during worker matching.
     */
    @Test
    void invalidInput_rejectsMalformedContracts() {
        for (String id : ObjectArrayView.of("invalid", "UPPER:id", "test:", ":missing_namespace", "test:two:colons")) {
            assertThrows(IllegalArgumentException.class, () -> RecipeType.of(id, (r, i) -> true));
            assertThrows(IllegalArgumentException.class, () -> new RecipeEntry<>(id, LINEAR, new Value(1, 1)));
        }
        assertThrows(NullPointerException.class, () -> new RecipeEntry<>("test:one", LINEAR, null));
        assertThrows(NullPointerException.class, () -> new RecipeManager().replaceAll(Arrays.asList((RecipeEntry<?>) null)));
        assertThrows(NullPointerException.class, () -> RecipeType.of("test:one", null));
        String longId = "test:" + "nested/".repeat(50) + "recipe";
        assertEquals(longId, new RecipeEntry<>(longId, LINEAR, new Value(1, 1)).id());
    }

    /**
     * Forces four readers to overlap hundreds of rebuilds; every captured snapshot must contain
     * only one generation's two entries, and previously captured lookup objects remain independent.
     *
     * @throws Exception if concurrent validation fails or exceeds the deadline
     */
    @Test
    void publication_manyReadersDuringReload_neverSeesMixedGeneration() throws Exception {
        var manager = new RecipeManager();
        var type = RecipeType.<Integer, Value>indexed("test:concurrent", (r, i) -> true, i -> i, r -> new int[] {0});
        manager.replaceAll(ObjectArrayView.of(new RecipeEntry<>("test:a", type, new Value(0, 0)),
                new RecipeEntry<>("test:b", type, new Value(0, 0))));
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(4);
        var running = new AtomicBoolean(true);
        var reads = new AtomicInteger();
        var tasks = new ObjectArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int reader = 0; reader < 4; reader++) {
                tasks.add(executor.submit(() -> {
                    start.countDown();
                    while (running.get()) {
                        RecipeSnapshot captured = manager.snapshot();
                        var lookup = captured.lookup(type);
                        assertEquals(2, lookup.size());
                        assertEquals(lookup.entries().get(0).value().generation(), lookup.entries().get(1).value().generation());
                        assertSame(captured.byId("test:a"), lookup.first(0));
                        reads.incrementAndGet();
                    }
                }));
            }
            assertTrue(start.await(5, TimeUnit.SECONDS));
            for (int generation = 1; generation <= 500; generation++) {
                manager.replaceAll(ObjectArrayView.of(new RecipeEntry<>("test:a", type, new Value(0, generation)),
                        new RecipeEntry<>("test:b", type, new Value(0, generation))));
            }
            running.set(false);
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            assertTrue(reads.get() > 0);
            assertEquals(501, manager.snapshot().generation());
        } finally {
            running.set(false);
            executor.shutdownNow();
        }
    }

    /**
     * @param suffix recipe path
     * @param type category
     * @param key match key
     * @return immutable fixture entry
     */
    private static RecipeEntry<Value> entry(String suffix, RecipeType<Integer, Value> type, int key) {
        return new RecipeEntry<>("test:" + suffix, type, new Value(key, 0));
    }

    /**
     * @param key candidate key
     * @param generation immutable fixture generation marker
     */
    private record Value(int key, int generation) {}
}
