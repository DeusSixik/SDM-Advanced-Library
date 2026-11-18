package dev.sixik.sdmal.core.repository.mongo;

import dev.sixik.sdmal.api.repository.RepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.internal.driver.bson.BsonDocument;
import dev.sixik.sdmal.internal.driver.mongodb.ConnectionString;
import dev.sixik.sdmal.internal.driver.mongodb.MongoClientSettings;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClients;
import dev.sixik.sdmal.internal.driver.mongodb.event.CommandListener;
import dev.sixik.sdmal.internal.driver.mongodb.event.CommandStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Records actual commands sent by the real MongoDB driver, including fields whose unchanged values
 * would be omitted from change-stream descriptions even if unnecessarily transmitted.
 */
@EnabledIfSystemProperty(named = "sdmal.test.mongo.uri", matches = ".+")
class MongoPatchCommandTest {
    /**
     * Explicitly supplied disposable endpoint; never a mock MongoDB server.
     */
    private static final String URI = System.getProperty("sdmal.test.mongo.uri");

    /**
     * Checks wire-command shape and payload, not only the resulting document or change event.
     */
    @Test
    void patch_wireCommand_excludesUnchangedFieldsAndUsesConditionalOperators() {
        String database = "sdmal_patch_wire_" + UUID.randomUUID().toString().replace("-", "");
        AtomicReference<BsonDocument> lastUpdate = new AtomicReference<>();
        var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(URI))
                .addCommandListener(new CommandListener() {
                    /**
                     * Retains the real outbound update command for assertions.
                     *
                     * @param event driver command event
                     */
                    @Override
                    public void commandStarted(CommandStartedEvent event) {
                        if (event.getCommandName().equals("update")) {
                            lastUpdate.set(event.getCommand().clone());
                        }
                    }
                }).build();
        var definition = new RepositoryDefinition<String, Value>(s -> s, s -> s, Value::id,
                RepositoryCodec.gson(Value.class));
        try (var manager = new MongoRepositoryManager(MongoClients.create(settings), database)) {
            var repository = manager.openPatchable("shops", definition);
            repository.upsert("one", new Value("one", 7, "unchanged_large_field_" + "z".repeat(262144), "remove me"));
            var before = repository.findVersioned("one").orElseThrow();
            lastUpdate.set(null);
            repository.patch("one", before.revision(), p -> p.set("amount", 42).unset("note"));
            BsonDocument command = lastUpdate.get();
            assertNotNull(command);
            var operation = command.getArray("updates").get(0).asDocument();
            var update = operation.getDocument("u");
            assertEquals(2, update.size());
            assertEquals(2, update.getDocument("$set").size());
            assertEquals("42", update.getDocument("$set").getString("fields.amount").getValue());
            assertTrue(update.getDocument("$set").containsKey("revision"));
            assertEquals(1, update.getDocument("$unset").size());
            assertTrue(update.getDocument("$unset").containsKey("fields.note"));
            assertFalse(operation.containsKey("upsert") && operation.getBoolean("upsert").getValue());
            assertTrue(operation.getDocument("q").toJson().contains(before.revision().toString()));
            assertFalse(command.toJson().contains("unchanged_large_field_"));
            assertTrue(command.toJson().length() < 2048);
            assertEquals(42, repository.find("one").orElseThrow().amount());
        } finally {
            try (var client = MongoClients.create(URI)) {
                client.getDatabase(database).drop();
            }
        }
    }

    /**
     * @param id stable application key
     * @param amount changed numeric field
     * @param unchanged large field excluded from the update command
     * @param note field removed through a native unset operator
     */
    private record Value(String id, int amount, String unchanged, String note) {}
}
