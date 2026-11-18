package dev.sixik.sdmal.api.repository;

/**
 * Owns backend resources and opens named repositories with application codecs.
 */
public interface RepositoryManager extends AutoCloseable {
    /**
     * Opens a named repository.
     *
     * @param name       backend-safe repository name
     * @param definition stable key and value codecs
     * @param <K>        key type
     * @param <V>        value type
     * @return repository handle owned by this manager
     */
    <K, V> Repository<K, V> open(String name, RepositoryDefinition<K, V> definition);

    /**
     * Opens opt-in field storage in a separate namespace from ordinary open(name, definition).
     * Existing ordinary records are not converted; use Repositories.copy explicitly to migrate.
     * SQL and MongoDB implement this operation. The codec must produce a JSON object with safe
     * top-level names; opaque NBT/component codecs require a custom JSON projection instead.
     *
     * @param name backend-safe logical repository name
     * @param definition application JSON-object codec and key mapping
     * @param <K> key type
     * @param <V> value type
     * @return repository with optimistic field updates
     * @throws UnsupportedOperationException when the backend has no field-update support
     */
    default <K, V> PatchableRepository<K, V> openPatchable(
            String name, RepositoryDefinition<K, V> definition) {
        throw new UnsupportedOperationException("This repository backend does not support field updates");
    }

    /**
     * Closes the manager and its owned backend resources.
     */
    @Override
    void close();
}
