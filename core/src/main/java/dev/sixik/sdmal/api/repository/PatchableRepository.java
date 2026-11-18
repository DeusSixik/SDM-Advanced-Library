package dev.sixik.sdmal.api.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Opt-in field storage for codecs producing a JSON object. Patches send only selected top-level
 * fields to the backend. They currently read the complete record to validate decoding and identity.
 * Full upsert and delete remain unconditional; each full upsert also invalidates old revisions.
 * Files and opaque NBT/base64 codecs are not supported by this contract.
 *
 * @param <K> key type
 * @param <V> value type
 */
public interface PatchableRepository<K, V> extends Repository<K, V> {
    /**
     * Reads a value and revision from the same database snapshot.
     *
     * @param key stable application key
     * @return complete value with its revision, or empty when missing
     */
    Optional<RepositorySnapshot<V>> findVersioned(K key);

    /**
     * Applies a nonempty field patch atomically if the expected revision still exists.
     * The candidate must decode successfully and preserve its extracted key.
     *
     * @param key existing record key
     * @param expectedRevision revision returned by findVersioned or the preceding patch
     * @param patch top-level field operations
     * @return locally decoded committed candidate and new revision; another writer may change it later
     * @throws RepositoryConflictException if missing, replaced, deleted, or concurrently changed
     * @throws IllegalArgumentException for an empty patch or a changed entity key
     */
    RepositorySnapshot<V> patch(K key, UUID expectedRevision, RepositoryPatch patch);

    /**
     * Builds and applies a conditional patch. The callback executes once on the calling thread.
     *
     * @param key existing record key
     * @param expectedRevision token for the record being edited
     * @param changes callback collecting field changes
     * @return committed candidate with new revision
     */
    default RepositorySnapshot<V> patch(
            K key, UUID expectedRevision, Consumer<RepositoryPatch> changes) {
        Objects.requireNonNull(changes, "changes");
        RepositoryPatch patch = new RepositoryPatch();
        changes.accept(patch);
        return patch(key, expectedRevision, patch);
    }
}
