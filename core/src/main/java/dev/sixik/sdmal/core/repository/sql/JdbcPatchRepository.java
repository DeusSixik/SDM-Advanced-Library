package dev.sixik.sdmal.core.repository.sql;

import dev.sixik.sdmal.api.repository.PatchableRepository;
import dev.sixik.sdmal.api.repository.RepositoryConflictException;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.api.repository.RepositoryPatch;
import dev.sixik.sdmal.api.repository.RepositorySnapshot;
import dev.sixik.sdmal.core.repository.RepositoryFields;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Portable field storage: a revision row owns independently writable JSON-fragment rows.
 * Every mutation locks or conditionally updates the revision before changing fields in one
 * transaction. Reads use a single join so revisions and values cannot come from separate reads.
 *
 * @param <K> application key type
 * @param <V> application value type
 */
final class JdbcPatchRepository<K, V> implements PatchableRepository<K, V> {
    /**
     * Shared identity and revision table, separate from the ordinary repository format.
     */
    private static final String ENTRIES = "sdmal_repository_documents";
    /**
     * Top-level field fragments, indexed by repository, key, and field name.
     */
    private static final String FIELDS = "sdmal_repository_fields";
    /**
     * One-statement read joining a revision with all of its field fragments.
     */
    private static final String SELECT = "SELECT e.entry_key, e.revision, f.field_name, f.field_json FROM "
            + ENTRIES + " e LEFT JOIN " + FIELDS
            + " f ON e.repository_name = f.repository_name AND e.entry_key = f.entry_key"
            + " WHERE e.repository_name = ?";
    /**
     * Factory of operation-owned connections.
     */
    private final JdbcRepositoryManager.ConnectionFactory connections;
    /**
     * Verifies the owning manager has not closed.
     */
    private final Runnable ownerCheck;
    /**
     * Validated logical repository partition.
     */
    private final String name;
    /**
     * Application key mapping and object codec.
     */
    private final RepositoryDefinition<K, V> definition;
    /**
     * Permanent handle close state.
     */
    private volatile boolean closed;

    /**
     * Creates the field schema when absent.
     *
     * @param connections operation-owned connection factory
     * @param ownerCheck manager lifetime check
     * @param name validated repository partition
     * @param definition application object codec
     */
    JdbcPatchRepository(JdbcRepositoryManager.ConnectionFactory connections, Runnable ownerCheck,
            String name, RepositoryDefinition<K, V> definition) {
        this.connections = connections;
        this.ownerCheck = ownerCheck;
        this.name = name;
        this.definition = definition;
        try (Connection connection = connections.open(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            String text = product.contains("mysql") || product.contains("mariadb") ? "LONGTEXT" : "TEXT";
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + ENTRIES
                    + " (repository_name VARCHAR(128) NOT NULL, entry_key VARCHAR(512) NOT NULL,"
                    + " revision VARCHAR(36) NOT NULL, PRIMARY KEY(repository_name, entry_key))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + FIELDS
                    + " (repository_name VARCHAR(128) NOT NULL, entry_key VARCHAR(512) NOT NULL,"
                    + " field_name VARCHAR(128) NOT NULL, field_json " + text + " NOT NULL,"
                    + " PRIMARY KEY(repository_name, entry_key, field_name),"
                    + " FOREIGN KEY(repository_name, entry_key) REFERENCES " + ENTRIES
                    + "(repository_name, entry_key) ON DELETE CASCADE)");
        } catch (SQLException failure) {
            throw new RepositoryException("Cannot initialize SQL field repository", failure);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<V> find(K key) {
        return findVersioned(key).map(RepositorySnapshot::value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<RepositorySnapshot<V>> findVersioned(K key) {
        checkOpen();
        String encoded = definition.encodeKey(key);
        try (Connection connection = connections.open()) {
            return read(connection, encoded).map(stored -> snapshot(encoded, stored));
        } catch (SQLException failure) {
            throw new RepositoryException("Cannot load SQL field record " + encoded, failure);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<K, V> findAll() {
        checkOpen();
        Map<String, StoredFields> records = new Object2ObjectOpenHashMap<>();
        try (Connection connection = connections.open(); var statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1);
                    StoredFields stored = records.get(key);
                    if (stored == null) {
                        stored = new StoredFields(revision(rows.getString(2)), new Object2ObjectOpenHashMap<>());
                        records.put(key, stored);
                    }
                    readField(rows, stored.fields());
                }
            }
        } catch (SQLException failure) {
            throw new RepositoryException("Cannot load SQL field repository " + name, failure);
        }
        Map<K, V> values = new Object2ObjectOpenHashMap<>();
        for (var entry : records.entrySet()) {
            V value = snapshot(entry.getKey(), entry.getValue()).value();
            if (values.putIfAbsent(definition.keyExtractor().apply(value), value) != null) {
                throw new RepositoryException("Duplicate SQL field key " + entry.getKey());
            }
        }
        return values;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void upsert(K key, V value) {
        checkOpen();
        String encoded = definition.verifyEntityKey(key, value);
        Map<String, String> fields = RepositoryFields.split(definition.codec().encode(value));
        RepositoryFields.decode(definition, encoded, fields);
        try {
            replace(encoded, fields);
        } catch (SQLException failure) {
            if (failure.getSQLState() == null || !failure.getSQLState().startsWith("23")) {
                throw new RepositoryException("Cannot save SQL field record " + encoded, failure);
            }
            /*
             * A concurrent insert can win between UPDATE and INSERT. A new transaction observes
             * that committed record and performs the ordinary unconditional replacement.
             */
            try {
                replace(encoded, fields);
            } catch (SQLException retryFailure) {
                retryFailure.addSuppressed(failure);
                throw new RepositoryException("Cannot retry SQL field record " + encoded, retryFailure);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RepositorySnapshot<V> patch(K key, UUID expectedRevision, RepositoryPatch patch) {
        checkOpen();
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Map<String, String> changes = Objects.requireNonNull(patch, "patch").changes();
        String encoded = definition.encodeKey(key);
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                StoredFields current = read(connection, encoded)
                        .orElseThrow(() -> new RepositoryConflictException(encoded));
                if (!expectedRevision.equals(current.revision())) {
                    throw new RepositoryConflictException(encoded);
                }
                Map<String, String> candidate = RepositoryFields.apply(current.fields(), changes);
                V value = RepositoryFields.decode(definition, encoded, candidate);
                UUID revision = UUID.randomUUID();
                if (!updateRevision(connection, encoded, revision, expectedRevision)) {
                    throw new RepositoryConflictException(encoded);
                }
                writeChanges(connection, encoded, changes);
                connection.commit();
                return new RepositorySnapshot<>(value, revision);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new RepositoryException("Cannot patch SQL field record " + encoded, failure);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean delete(K key) {
        checkOpen();
        String encoded = definition.encodeKey(key);
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                boolean deleted;
                try (var statement = connection.prepareStatement(
                        "DELETE FROM " + ENTRIES + " WHERE repository_name = ? AND entry_key = ?")) {
                    statement.setString(1, name);
                    statement.setString(2, encoded);
                    deleted = statement.executeUpdate() != 0;
                }
                if (!deleted) {
                    connection.commit();
                    return false;
                }
                /*
                 * Explicit cleanup also supports connections whose foreign-key enforcement is
                 * disabled. The entry DELETE holds the write lock until this transaction commits.
                 */
                try (var statement = connection.prepareStatement(
                        "DELETE FROM " + FIELDS + " WHERE repository_name = ? AND entry_key = ?")) {
                    statement.setString(1, name);
                    statement.setString(2, encoded);
                    statement.executeUpdate();
                }
                connection.commit();
                return deleted;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new RepositoryException("Cannot delete SQL field record " + encoded, failure);
        }
    }

    /**
     * Permanently closes this handle.
     */
    @Override
    public void close() {
        closed = true;
    }

    /**
     * Reads exactly one coherent revision and its fields in one statement.
     *
     * @param connection active operation connection
     * @param key encoded entry key
     * @return stored fields or empty
     * @throws SQLException on read failure
     */
    private Optional<StoredFields> read(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT + " AND e.entry_key = ?")) {
            statement.setString(1, name);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                StoredFields stored = new StoredFields(revision(rows.getString(2)), new Object2ObjectOpenHashMap<>());
                do {
                    readField(rows, stored.fields());
                } while (rows.next());
                return Optional.of(stored);
            }
        }
    }

    /**
     * Copies a joined field, allowing the empty-document LEFT JOIN row.
     *
     * @param rows current result row
     * @param fields target document
     * @throws SQLException on read failure
     */
    private static void readField(ResultSet rows, Map<String, String> fields) throws SQLException {
        String field = rows.getString(3);
        if (field == null) {
            return;
        }
        fields.put(field, rows.getString(4));
    }

    /**
     * Validates persisted data and creates a detached snapshot.
     *
     * @param key encoded record identity
     * @param stored coherent stored fields
     * @return validated application snapshot
     */
    private RepositorySnapshot<V> snapshot(String key, StoredFields stored) {
        try {
            return new RepositorySnapshot<>(RepositoryFields.decode(definition, key, stored.fields()), stored.revision());
        } catch (RuntimeException failure) {
            throw new RepositoryException("Cannot decode SQL field record " + key, failure);
        }
    }

    /**
     * Replaces a complete document under its revision-row write lock.
     *
     * @param key encoded identity
     * @param fields complete replacement fields
     * @throws SQLException on write failure
     */
    private void replace(String key, Map<String, String> fields) throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                UUID revision = UUID.randomUUID();
                if (!updateRevision(connection, key, revision, null)) {
                    try (var statement = connection.prepareStatement("INSERT INTO " + ENTRIES
                            + " (repository_name, entry_key, revision) VALUES (?, ?, ?)")) {
                        statement.setString(1, name);
                        statement.setString(2, key);
                        statement.setString(3, revision.toString());
                        statement.executeUpdate();
                    }
                }
                try (var statement = connection.prepareStatement("DELETE FROM " + FIELDS
                        + " WHERE repository_name = ? AND entry_key = ?")) {
                    statement.setString(1, name);
                    statement.setString(2, key);
                    statement.executeUpdate();
                }
                insertFields(connection, key, fields);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    /**
     * Changes a revision, acquiring the document write lock before any field mutation.
     *
     * @param connection active transaction
     * @param key encoded identity
     * @param revision new unpredictable write token
     * @param expected previous token, or null for unconditional replacement
     * @return true when a row matched
     * @throws SQLException on write failure
     */
    private boolean updateRevision(Connection connection, String key, UUID revision, UUID expected) throws SQLException {
        String condition = expected == null ? "" : " AND revision = ?";
        try (var statement = connection.prepareStatement("UPDATE " + ENTRIES
                + " SET revision = ? WHERE repository_name = ? AND entry_key = ?" + condition)) {
            statement.setString(1, revision.toString());
            statement.setString(2, name);
            statement.setString(3, key);
            if (expected != null) {
                statement.setString(4, expected.toString());
            }
            return statement.executeUpdate() != 0;
        }
    }

    /**
     * Removes only touched fields, then inserts the supplied replacements.
     *
     * @param connection active transaction holding the revision lock
     * @param key encoded identity
     * @param changes captured replacements or removals
     * @throws SQLException on write failure
     */
    private void writeChanges(Connection connection, String key, Map<String, String> changes) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM " + FIELDS
                + " WHERE repository_name = ? AND entry_key = ? AND field_name = ?")) {
            for (String field : changes.keySet()) {
                statement.setString(1, name);
                statement.setString(2, key);
                statement.setString(3, field);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        insertFields(connection, key, changes);
    }

    /**
     * Sends only nonremoved fragments, without including other document fields.
     *
     * @param connection active transaction
     * @param key encoded identity
     * @param fields replacements; null entries are skipped
     * @throws SQLException on write failure
     */
    private void insertFields(Connection connection, String key, Map<String, String> fields) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + FIELDS
                + " (repository_name, entry_key, field_name, field_json) VALUES (?, ?, ?, ?)")) {
            boolean populated = false;
            for (var entry : fields.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                statement.setString(1, name);
                statement.setString(2, key);
                statement.setString(3, entry.getKey());
                statement.setString(4, entry.getValue());
                statement.addBatch();
                populated = true;
            }
            if (populated) {
                statement.executeBatch();
            }
        }
    }

    /**
     * Preserves the original failure while releasing a failed transaction.
     *
     * @param connection failed transaction
     * @param failure original error receiving rollback diagnostics
     */
    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /**
     * Validates a stored write token and reports corruption as a repository failure.
     *
     * @param token persisted UUID text
     * @return validated opaque revision
     */
    private static UUID revision(String token) {
        try {
            return UUID.fromString(token);
        } catch (RuntimeException failure) {
            throw new RepositoryException("Invalid SQL repository revision", failure);
        }
    }

    /**
     * Checks both handle and owner lifetime.
     *
     * @throws IllegalStateException after closure
     */
    private void checkOpen() {
        ownerCheck.run();
        if (closed) {
            throw new IllegalStateException("SQL field repository is closed");
        }
    }

    /**
     * Internal coherent document representation.
     *
     * @param revision opaque write token
     * @param fields complete top-level JSON fragments
     */
    private record StoredFields(UUID revision, Map<String, String> fields) {}
}
