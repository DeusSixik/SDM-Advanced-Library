package dev.sixik.sdmal.api.repository;

import dev.sixik.sdmal.core.repository.file.FileRepositoryManager;
import dev.sixik.sdmal.core.repository.mongo.MongoRepositoryManager;
import dev.sixik.sdmal.core.repository.sql.JdbcRepositoryManager;

import java.nio.file.Path;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Built-in repository backends; callers depend only on the repository API.
 */
public final class Repositories {
    /**
     * Prevents construction.
     */
    private Repositories() {
    }

    /**
     * @param root collection root
     * @return JSON file manager
     */
    public static RepositoryManager json(Path root) {
        return new FileRepositoryManager(root);
    }

    /**
     * @param root collection root
     * @return compressed NBT file manager
     */
    public static RepositoryManager nbt(Path root) {
        return new FileRepositoryManager(root, true);
    }

    /**
     * Opens the bundled H2 database. Its driver is isolated from other mods' database drivers.
     *
     * @param url H2 JDBC URL, for example jdbc:h2:file:./data/shop
     * @return SQL manager; no external driver dependency is required
     */
    public static RepositoryManager h2(String url) {
        if (url == null || !url.startsWith("jdbc:h2:"))
            throw new IllegalArgumentException("Expected jdbc:h2: URL");
        try {
            Class.forName("dev.sixik.sdmal.internal.driver.h2.Driver");
        } catch (ClassNotFoundException failure) {
            throw new RepositoryException("Bundled H2 driver is missing", failure);
        }
        return new JdbcRepositoryManager(url);
    }

    /**
     * Uses a caller-provided JDBC driver. Data is stored as JSON documents in a keyed SQL table.
     *
     * @param url JDBC URL; the corresponding driver must be supplied by the application
     * @return SQL manager
     */
    public static RepositoryManager jdbc(String url) {
        return new JdbcRepositoryManager(url);
    }

    /**
     * Uses a caller-owned connection pool or DataSource; operations close borrowed connections.
     *
     * @param source connection source; the manager never closes the source itself
     * @return SQL manager
     */
    public static RepositoryManager jdbc(DataSource source) {
        Objects.requireNonNull(source, "source");
        return new JdbcRepositoryManager(source::getConnection);
    }

    /**
     * @param uri      MongoDB URI
     * @param database database name
     * @return manager owning a MongoClient
     */
    public static RepositoryManager mongo(String uri, String database) {
        return new MongoRepositoryManager(uri, database);
    }

    /**
     * Copies a detached source snapshot using the same application definition in both backends.
     * Source data is retained; destination entries are upserted. A failure can leave a partial
     * copy, so verify the destination before switching the application's configured backend.
     *
     * @param source      repository to read
     * @param destination repository to populate
     * @param <K>         key type
     * @param <V>         value type
     * @return number of copied entries
     */
    public static <K, V> int copy(Repository<K, V> source, Repository<K, V> destination) {
        var snapshot = source.findAll();
        destination.upsertAll(snapshot);
        return snapshot.size();
    }
}
