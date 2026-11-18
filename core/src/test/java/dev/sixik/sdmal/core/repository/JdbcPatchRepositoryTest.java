package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.repository.*;
import dev.sixik.sdmal.core.repository.sql.JdbcRepositoryManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the field repository contract and SQL-payload checks against the bundled real H2.
 */
class JdbcPatchRepositoryTest extends PatchRepositoryContract {
    /**
     * One independent database per test; multiple managers in the same test share it.
     */
    private final String url = "jdbc:h2:mem:patch_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    /**
     * @return manager connected to this test's isolated H2 database
     */
    @Override
    RepositoryManager manager() {
        return Repositories.h2(url);
    }

    /**
     * Captures actual JDBC write bindings, proving unchanged large fields are never rebound.
     */
    @Test
    void patch_jdbcWrites_bindOnlyChangedFieldsAndRevision() throws Exception {
        String unchanged = "unchanged_large_field_" + "z".repeat(262144);
        try (var driver = manager()) {
            var spy = new JdbcSpy(url);
            try (var manager = new JdbcRepositoryManager(spy::open)) {
                var repository = manager.openPatchable("shops", DEFINITION);
                repository.upsert("one", new Value("one", 7, unchanged, initial().tags(), initial().details()));
                var before = repository.findVersioned("one").orElseThrow();
                spy.writes.clear();
                repository.patch("one", before.revision(), p -> p.set("amount", 42));
                assertTrue(spy.writes.contains("amount"));
                assertTrue(spy.writes.contains("42"));
                for (String binding : spy.writes) {
                    assertFalse(binding.contains("unchanged_large_field_"));
                    assertNotEquals("details", binding);
                    assertNotEquals("tags", binding);
                    assertNotEquals("note", binding);
                }
                assertEquals(unchanged, repository.find("one").orElseThrow().note());
                try (Connection connection = DriverManager.getConnection(url);
                        var query = connection.createStatement();
                        var rows = query.executeQuery("SELECT COUNT(*) FROM sdmal_repository_fields")) {
                    assertTrue(rows.next());
                    assertEquals(5, rows.getInt(1));
                }
            }
        }
    }

    /**
     * An injected failure after CAS and field deletion must roll back the complete transaction.
     */
    @Test
    void patch_insertFailure_rollsBackFieldsAndRevision() {
        try (var driver = manager()) {
            var spy = new JdbcSpy(url);
            try (var manager = new JdbcRepositoryManager(spy::open)) {
                var repository = manager.openPatchable("shops", DEFINITION);
                repository.upsert("one", initial());
                var before = repository.findVersioned("one").orElseThrow();
                spy.failInsertion.set(true);
                assertThrows(RepositoryException.class,
                        () -> repository.patch("one", before.revision(), p -> p.set("amount", 777).unset("note")));
                assertEquals(before, repository.findVersioned("one").orElseThrow());
                var committed = repository.patch("one", before.revision(), p -> p.set("amount", 42));
                assertEquals(42, committed.value().amount());
            }
        }
    }

    /**
     * Records SQL write parameters while forwarding every operation to a real JDBC driver.
     */
    private static final class JdbcSpy {
        /**
         * Real isolated H2 endpoint.
         */
        private final String url;
        /**
         * Captured strings bound to INSERT, UPDATE, or DELETE statements.
         */
        private final ObjectArrayList<String> writes = new ObjectArrayList<>();
        /**
         * One-shot failure armed immediately before the tested patch.
         */
        private final AtomicBoolean failInsertion = new AtomicBoolean();

        /**
         * @param url real H2 endpoint
         */
        private JdbcSpy(String url) {
            this.url = url;
        }

        /**
         * @return tracing connection forwarding transactions to H2
         * @throws SQLException if the real connection cannot open
         */
        private Connection open() throws SQLException {
            Connection real = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(real, arguments);
                            if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql) {
                                return trace((PreparedStatement) result, sql);
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        /**
         * @param real real H2 prepared statement
         * @param sql prepared SQL text
         * @return transparent tracing statement
         */
        private PreparedStatement trace(PreparedStatement real, String sql) {
            boolean mutating = sql.startsWith("INSERT") || sql.startsWith("UPDATE") || sql.startsWith("DELETE");
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                    (proxy, method, arguments) -> {
                        if (mutating && method.getName().equals("setString")) {
                            writes.add((String) arguments[1]);
                        }
                        if (sql.startsWith("INSERT INTO sdmal_repository_fields")
                                && method.getName().equals("executeBatch") && failInsertion.compareAndSet(true, false)) {
                            throw new SQLException("Injected failure after revision update", "HY000");
                        }
                        try {
                            return method.invoke(real, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
