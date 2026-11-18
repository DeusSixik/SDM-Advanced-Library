package dev.sixik.sdmal.core.repository.sql;

import com.google.gson.JsonParser;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.api.repository.RepositoryManager;
import dev.sixik.sdmal.api.repository.PatchableRepository;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.ApiStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC backend for SQL databases with standard table, transaction, and text-column support.
 */
@ApiStatus.Internal
public final class JdbcRepositoryManager implements RepositoryManager {
    /**
     * Shared SQL table; repository names partition its rows.
     */
    private static final String TABLE = "sdmal_repository_entries";

    /**
     * Factory supplying operation-owned JDBC connections.
     */
    private final ConnectionFactory connections;

    /**
     * Permanent closed state; further operations are rejected.
     */
    private boolean closed;

    /**
     * Creates a manager using the JDBC driver registered for the supplied URL. The matching
     * database driver must be present on the runtime classpath.
     *
     * @param jdbcUrl JDBC connection URL
     */
    public JdbcRepositoryManager(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        connections = () -> DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Creates a manager using caller-owned connection provisioning such as a DataSource.
     *
     * @param connections factory returning a fresh connection owned by the caller of the factory
     */
    public JdbcRepositoryManager(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Opens a SQL repository and creates the shared table when needed.
     *
     * @param name       repository partition name
     * @param definition stable key and value codecs
     * @param <K>        key type
     * @param <V>        value type
     * @return JDBC-backed repository
     */
    @Override
    public synchronized <K, V> Repository<K, V> open(
            String name, RepositoryDefinition<K, V> definition) {
        requireOpen();
        validateName(name);
        Objects.requireNonNull(definition, "definition");
        try (Connection connection = connections.open();
             var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            boolean mysql =
                    connection
                            .getMetaData()
                            .getDatabaseProductName()
                            .toLowerCase(java.util.Locale.ROOT)
                            .matches(".*(mysql|mariadb).*");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS "
                            + TABLE
                            + " ("
                            + "repository_name VARCHAR(128) NOT NULL, "
                            + "entry_key VARCHAR(512) NOT NULL, "
                            + (mysql
                            ? "payload_json LONGTEXT NOT NULL, "
                            : "payload_json TEXT NOT NULL, ")
                            + "PRIMARY KEY (repository_name, entry_key))");
        } catch (SQLException failure) {
            throw new RepositoryException("Cannot initialize SQL repository table", failure);
        }
        return new JdbcRepository<>(this, name, definition);
    }

    /**
     * Opens separate, transactional per-field tables without changing legacy payload_json rows.
     *
     * @param name logical repository partition
     * @param definition JSON-object codec and identity mapping
     * @param <K> key type
     * @param <V> value type
     * @return conditional field repository
     */
    @Override
    public synchronized <K, V> PatchableRepository<K, V> openPatchable(
            String name, RepositoryDefinition<K, V> definition) {
        requireOpen();
        validateName(name);
        return new JdbcPatchRepository<>(connections, this::requireOpen, name,
                Objects.requireNonNull(definition, "definition"));
    }

    /**
     * Closes the manager without closing caller-owned DataSources.
     */
    @Override
    public synchronized void close() {
        closed = true;
    }

    /**
     * @throws IllegalStateException if the manager has closed
     */
    private synchronized void requireOpen() {
        if (closed) throw new IllegalStateException("JDBC repository manager is closed");
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
     * Opens one fresh connection.
     */
    @FunctionalInterface
    public interface ConnectionFactory {
        /**
         * @return connection that the repository operation will close
         * @throws SQLException if the database cannot be reached
         */
        Connection open() throws SQLException;
    }

    private static final class JdbcRepository<K, V> implements Repository<K, V> {
        /**
         * Owning backend session; handles cannot outlive it.
         */
        private final JdbcRepositoryManager manager;

        /**
         * Validated collection partition name, at most 128 characters.
         */
        private final String name;

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
         * @param name       collection partition
         * @param definition application codecs
         */
        private JdbcRepository(
                JdbcRepositoryManager manager, String name, RepositoryDefinition<K, V> definition) {
            this.manager = manager;
            this.name = name;
            this.definition = definition;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized Optional<V> find(K key) {
            checkOpen();
            String encodedKey = definition.encodeKey(key);
            try (Connection connection = manager.connections.open();
                 var statement =
                         connection.prepareStatement(
                                 "SELECT payload_json FROM "
                                         + TABLE
                                         + " WHERE repository_name = ? AND entry_key = ?")) {
                statement.setString(1, name);
                statement.setString(2, encodedKey);
                try (var result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(decode(encodedKey, result.getString(1)))
                            : Optional.empty();
                }
            } catch (SQLException failure) {
                throw new RepositoryException(
                        "Cannot load SQL repository key " + encodedKey, failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized Map<K, V> findAll() {
            checkOpen();
            Map<K, V> values = new Object2ObjectOpenHashMap<>();
            try (Connection connection = manager.connections.open();
                 var statement =
                         connection.prepareStatement(
                                 "SELECT entry_key, payload_json FROM "
                                         + TABLE
                                         + " WHERE repository_name = ?")) {
                statement.setString(1, name);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        String encodedKey = result.getString(1);
                        V value = decode(encodedKey, result.getString(2));
                        K key =
                                Objects.requireNonNull(
                                        definition.keyExtractor().apply(value), "extracted key");
                        if (values.putIfAbsent(key, value) != null)
                            throw new RepositoryException(
                                    "Duplicate SQL repository key " + encodedKey);
                    }
                }
                return values;
            } catch (SQLException failure) {
                throw new RepositoryException("Cannot load SQL repository " + name, failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void upsert(K key, V value) {
            checkOpen();
            String encodedKey = definition.verifyEntityKey(key, value);
            String json = Objects.requireNonNull(definition.codec().encode(value), "encoded JSON");
            JsonParser.parseString(json);
            SQLException original;
            try (Connection connection = manager.connections.open()) {
                connection.setAutoCommit(false);
                try {
                    if (!update(connection, encodedKey, json)) insert(connection, encodedKey, json);
                    connection.commit();
                    return;
                } catch (SQLException failure) {
                    original = failure;
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
            } catch (SQLException failure) {
                throw new RepositoryException(
                        "Cannot save SQL repository key " + encodedKey, failure);
            }
            if (isConstraintViolation(original) && retryUpdate(encodedKey, json)) return;
            throw new RepositoryException("Cannot save SQL repository key " + encodedKey, original);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized boolean delete(K key) {
            checkOpen();
            String encodedKey = definition.encodeKey(key);
            try (Connection connection = manager.connections.open();
                 var statement =
                         connection.prepareStatement(
                                 "DELETE FROM "
                                         + TABLE
                                         + " WHERE repository_name = ? AND entry_key = ?")) {
                statement.setString(1, name);
                statement.setString(2, encodedKey);
                connection.setAutoCommit(true);
                return statement.executeUpdate() != 0;
            } catch (SQLException failure) {
                throw new RepositoryException(
                        "Cannot delete SQL repository key " + encodedKey, failure);
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
         * @param storedKey SQL row key
         * @param json      stored payload
         * @return decoded value with matching identity
         */
        private V decode(String storedKey, String json) {
            try {
                V value = Objects.requireNonNull(definition.codec().decode(json), "decoded value");
                String actualKey =
                        definition.verifyEntityKey(
                                Objects.requireNonNull(
                                        definition.keyExtractor().apply(value), "extracted key"),
                                value);
                if (!storedKey.equals(actualKey))
                    throw new RepositoryException(
                            "Stored SQL key does not match entity payload: " + storedKey);
                return value;
            } catch (RepositoryException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new RepositoryException(
                        "Cannot decode SQL repository key " + storedKey, failure);
            }
        }

        /**
         * @param connection active transaction
         * @param key        encoded key
         * @param json       replacement payload
         * @return true if a row was updated
         * @throws SQLException on backend failure
         */
        private boolean update(Connection connection, String key, String json) throws SQLException {
            try (var statement =
                         connection.prepareStatement(
                                 "UPDATE "
                                         + TABLE
                                         + " SET payload_json = ?"
                                         + " WHERE repository_name = ? AND entry_key = ?")) {
                statement.setString(1, json);
                statement.setString(2, name);
                statement.setString(3, key);
                return statement.executeUpdate() != 0;
            }
        }

        /**
         * @param connection active transaction
         * @param key        encoded key
         * @param json       new payload
         * @throws SQLException on backend failure
         */
        private void insert(Connection connection, String key, String json) throws SQLException {
            try (var statement =
                         connection.prepareStatement(
                                 "INSERT INTO "
                                         + TABLE
                                         + " (repository_name, entry_key, payload_json) VALUES (?,"
                                         + " ?, ?)")) {
                statement.setString(1, name);
                statement.setString(2, key);
                statement.setString(3, json);
                statement.executeUpdate();
            }
        }

        /**
         * @param key  encoded key
         * @param json validated payload
         * @return true after replacement or an identical existing value
         */
        private boolean retryUpdate(String key, String json) {
            try (Connection connection = manager.connections.open()) {
                connection.setAutoCommit(true);
                if (update(connection, key, json)) return true;
                try (var statement =
                             connection.prepareStatement(
                                     "SELECT 1 FROM "
                                             + TABLE
                                             + " WHERE repository_name = ? AND entry_key = ?")) {
                    statement.setString(1, name);
                    statement.setString(2, key);
                    try (var result = statement.executeQuery()) {
                        return result.next();
                    }
                }
            } catch (SQLException failure) {
                throw new RepositoryException("Cannot retry SQL repository key " + key, failure);
            }
        }

        /**
         * @throws IllegalStateException if this handle or its manager has closed
         */
        private void checkOpen() {
            manager.requireOpen();
            if (closed) throw new IllegalStateException("SQL repository is closed");
        }

        /**
         * @param failure SQL error chain
         * @return true for a constraint violation that may result from a concurrent insert
         */
        private static boolean isConstraintViolation(SQLException failure) {
            for (SQLException current = failure;
                 current != null;
                 current = current.getNextException())
                if (current.getSQLState() != null && current.getSQLState().startsWith("23"))
                    return true;
            return false;
        }
    }
}
