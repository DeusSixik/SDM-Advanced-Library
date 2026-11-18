package dev.sixik.sdmal.core.repository.mongo;

import com.google.gson.JsonParser;
import dev.sixik.sdmal.api.repository.ChangeAwareRepository;
import dev.sixik.sdmal.api.repository.Repository;
import dev.sixik.sdmal.api.repository.RepositoryChange;
import dev.sixik.sdmal.api.repository.RepositoryDefinition;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.api.repository.RepositoryManager;
import dev.sixik.sdmal.api.repository.RepositorySubscription;
import dev.sixik.sdmal.api.repository.PatchableRepository;
import dev.sixik.sdmal.api.repository.RepositoryPatch;
import dev.sixik.sdmal.api.repository.RepositorySnapshot;
import dev.sixik.sdmal.api.repository.RepositoryConflictException;
import dev.sixik.sdmal.core.repository.RepositoryFields;
import dev.sixik.sdmal.internal.driver.bson.Document;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClient;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoClients;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoCollection;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoCursor;
import dev.sixik.sdmal.internal.driver.mongodb.client.MongoDatabase;
import dev.sixik.sdmal.internal.driver.mongodb.client.model.Filters;
import dev.sixik.sdmal.internal.driver.mongodb.client.model.ReplaceOptions;
import dev.sixik.sdmal.internal.driver.mongodb.client.model.changestream.ChangeStreamDocument;
import dev.sixik.sdmal.internal.driver.mongodb.client.model.changestream.OperationType;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MongoDB backend using one collection per named repository.
 */
@ApiStatus.Internal
public final class MongoRepositoryManager implements RepositoryManager {
    /**
     * MongoClient exclusively owned by this manager.
     */
    private final MongoClient client;

    /**
     * Database served by the owned client.
     */
    private final MongoDatabase database;

    /**
     * Permanent closed state; further operations are rejected.
     */
    private volatile boolean closed;

    /**
     * Repository handles whose subscriptions must stop when this client closes.
     */
    private final CopyOnWriteArrayList<MongoRepository<?, ?>> repositories =
            new CopyOnWriteArrayList<>();

    /**
     * Creates a manager for a MongoDB URI and database.
     *
     * @param uri          MongoDB connection URI
     * @param databaseName database name
     */
    public MongoRepositoryManager(String uri, String databaseName) {
        Objects.requireNonNull(uri, "uri");
        if (uri.isBlank()) throw new IllegalArgumentException("MongoDB URI is blank");
        validateName(databaseName);
        client = MongoClients.create(uri);
        database = client.getDatabase(databaseName);
    }

    /**
     * Takes ownership of a configured internal client, including its driver diagnostic listeners.
     *
     * @param ownedClient client closed by this manager
     * @param databaseName validated MongoDB database name
     */
    MongoRepositoryManager(MongoClient ownedClient, String databaseName) {
        validateName(databaseName);
        client = Objects.requireNonNull(ownedClient, "ownedClient");
        database = client.getDatabase(databaseName);
    }

    /**
     * Opens a collection-backed repository.
     *
     * @param name       MongoDB collection name
     * @param definition stable key and value codecs
     * @param <K>        key type
     * @param <V>        value type
     * @return MongoDB repository
     */
    @Override
    public synchronized <K, V> Repository<K, V> open(
            String name, RepositoryDefinition<K, V> definition) {
        requireOpen();
        validateName(name);
        Objects.requireNonNull(definition, "definition");
        var repository = new MongoRepository<>(this, database.getCollection(name), definition);
        repositories.add(repository);
        return repository;
    }

    /**
     * Opens a separately namespaced field collection. Its change stream uses the same subscription
     * contract as ordinary repositories, including patch notifications to RepositoryStorage.
     *
     * @param name logical collection name
     * @param definition JSON-object codec and identity mapping
     * @param <K> key type
     * @param <V> value type
     * @return revision-checked field repository
     */
    @Override
    public synchronized <K, V> PatchableRepository<K, V> openPatchable(
            String name, RepositoryDefinition<K, V> definition) {
        requireOpen();
        validateName(name);
        var repository = new MongoPatchRepository<>(this,
                database.getCollection("sdmal_fields_" + name), Objects.requireNonNull(definition, "definition"));
        repositories.add(repository);
        return repository;
    }

    /**
     * Closes the MongoClient owned by this manager.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            for (var repository : repositories) repository.close();
        } finally {
            repositories.clear();
            client.close();
        }
    }

    /**
     * @throws IllegalStateException if the manager has closed
     */
    private synchronized void requireOpen() {
        if (closed) throw new IllegalStateException("MongoDB repository manager is closed");
    }

    /**
     * @param name backend-safe collection name
     * @throws IllegalArgumentException for a blank or unsafe identifier
     */
    private static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9_.-]{1,120}") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException(
                    "Invalid MongoDB database or collection name: " + name);
    }

    private static class MongoRepository<K, V> implements ChangeAwareRepository<K, V> {
        /**
         * Owning backend session; handles cannot outlive it.
         */
        private final MongoRepositoryManager manager;

        /**
         * Thread-safe MongoDB collection used by this handle.
         */
        final MongoCollection<Document> collection;

        /**
         * Stable application key and value encoding contract.
         */
        final RepositoryDefinition<K, V> definition;

        /**
         * Permanent closed state; further operations are rejected.
         */
        private volatile boolean closed;

        /**
         * Explicit subscriptions; each owns its cursor and worker.
         */
        private final CopyOnWriteArrayList<RepositorySubscription> subscriptions =
                new CopyOnWriteArrayList<>();

        /**
         * @param manager    owning backend session
         * @param collection physical collection
         * @param definition application codecs
         */
        private MongoRepository(
                MongoRepositoryManager manager,
                MongoCollection<Document> collection,
                RepositoryDefinition<K, V> definition) {
            this.manager = manager;
            this.collection = collection;
            this.definition = definition;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Optional<V> find(K key) {
            checkOpen();
            String encodedKey = definition.encodeKey(key);
            try {
                Document record = collection.find(Filters.eq("_id", encodedKey)).first();
                return record == null ? Optional.empty() : Optional.of(decode(encodedKey, record));
            } catch (RuntimeException failure) {
                throw new RepositoryException("Cannot load MongoDB repository value", failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Map<K, V> findAll() {
            checkOpen();
            Map<K, V> values = new Object2ObjectOpenHashMap<>();
            try (var cursor = collection.find().iterator()) {
                while (cursor.hasNext()) {
                    Document record = cursor.next();
                    String encodedKey = record.getString("_id");
                    V value = decode(encodedKey, record);
                    K key =
                            Objects.requireNonNull(
                                    definition.keyExtractor().apply(value), "extracted key");
                    if (values.putIfAbsent(key, value) != null)
                        throw new RepositoryException(
                                "Duplicate MongoDB repository key " + encodedKey);
                }
            } catch (RuntimeException failure) {
                throw new RepositoryException("Cannot load MongoDB repository values", failure);
            }
            return values;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void upsert(K key, V value) {
            checkOpen();
            String encodedKey = definition.verifyEntityKey(key, value);
            String json = Objects.requireNonNull(definition.codec().encode(value), "encoded JSON");
            JsonParser.parseString(json);
            Document record = new Document("_id", encodedKey).append("payload_json", json);
            try {
                collection.replaceOne(
                        Filters.eq("_id", encodedKey), record, new ReplaceOptions().upsert(true));
            } catch (RuntimeException failure) {
                throw new RepositoryException(
                        "Cannot save MongoDB repository key " + encodedKey, failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean delete(K key) {
            checkOpen();
            String encodedKey = definition.encodeKey(key);
            try {
                return collection.deleteOne(Filters.eq("_id", encodedKey)).getDeletedCount() != 0;
            } catch (RuntimeException failure) {
                throw new RepositoryException(
                        "Cannot delete MongoDB repository key " + encodedKey, failure);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            for (var subscription : subscriptions) subscription.close();
            subscriptions.clear();
            manager.repositories.remove(this);
        }

        /**
         * @param encodedKey stored document key
         * @param record     database document
         * @return decoded value with matching identity
         */
        V decode(String encodedKey, Document record) {
            try {
                String payload = record.getString("payload_json");
                if (payload == null)
                    throw new RepositoryException("Missing MongoDB value payload: " + encodedKey);
                V value =
                        Objects.requireNonNull(definition.codec().decode(payload), "decoded value");
                String actualKey =
                        definition.verifyEntityKey(
                                Objects.requireNonNull(
                                        definition.keyExtractor().apply(value), "extracted key"),
                                value);
                if (!encodedKey.equals(actualKey))
                    throw new RepositoryException(
                            "Stored MongoDB key does not match entity payload: " + encodedKey);
                return value;
            } catch (RepositoryException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new RepositoryException(
                        "Cannot decode MongoDB repository key " + encodedKey, failure);
            }
        }

        /**
         * @throws IllegalStateException if this handle or its manager has closed
         */
        final void checkOpen() {
            manager.requireOpen();
            if (closed) throw new IllegalStateException("MongoDB repository is closed");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized RepositorySubscription subscribe(
                Consumer<RepositoryChange<K>> listener, Consumer<Throwable> onFailure) {
            checkOpen();
            Objects.requireNonNull(listener, "listener");
            Objects.requireNonNull(onFailure, "onFailure");
            MongoCursor<ChangeStreamDocument<Document>> cursor;
            try {
                cursor = collection.watch().maxAwaitTime(500, TimeUnit.MILLISECONDS).cursor();
            } catch (RuntimeException failure) {
                throw new RepositoryException(
                        "Cannot open MongoDB change stream; a replica set is required", failure);
            }
            AtomicBoolean stopped = new AtomicBoolean();
            Thread worker =
                    new Thread(
                            () -> {
                                try (cursor) {
                                    while (!stopped.get()) {
                                        var change = cursor.tryNext();
                                        if (change == null) continue;
                                        OperationType operation = change.getOperationType();
                                        if (operation == OperationType.INVALIDATE
                                                || operation == OperationType.DROP
                                                || operation == OperationType.RENAME
                                                || operation == OperationType.DROP_DATABASE)
                                            throw new RepositoryException(
                                                    "MongoDB collection invalidated; reload and"
                                                            + " resubscribe");
                                        if (change.getDocumentKey() == null) continue;
                                        RepositoryChange.Kind kind;
                                        if (operation == OperationType.DELETE)
                                            kind = RepositoryChange.Kind.DELETE;
                                        else if (operation == OperationType.INSERT
                                                || operation == OperationType.UPDATE
                                                || operation == OperationType.REPLACE)
                                            kind = RepositoryChange.Kind.UPSERT;
                                        else continue;
                                        K key =
                                                definition.decodeKey(
                                                        change.getDocumentKey()
                                                                .getString("_id")
                                                                .getValue());
                                        if (!stopped.get())
                                            listener.accept(new RepositoryChange<>(kind, key));
                                    }
                                } catch (RuntimeException failure) {
                                    if (!stopped.get()) onFailure.accept(failure);
                                }
                            },
                            "sdmal-mongo-" + collection.getNamespace().getCollectionName());
            worker.setDaemon(true);
            RepositorySubscription subscription =
                    new RepositorySubscription() {
                        /**
                         * Stops polling and releases the cursor; callbacks already running may
                         * finish.
                         */
                        @Override
                        public void close() {
                            if (!stopped.compareAndSet(false, true)) return;
                            cursor.close();
                            worker.interrupt();
                            subscriptions.remove(this);
                        }
                    };
            subscriptions.add(subscription);
            worker.start();
            return subscription;
        }
    }

    /**
     * Field repository retaining ordinary MongoDB change-stream behavior.
     *
     * @param <K> application key type
     * @param <V> application value type
     */
    private static final class MongoPatchRepository<K, V> extends MongoRepository<K, V>
            implements PatchableRepository<K, V> {
        /**
         * Creates a handle for the separate field collection.
         *
         * @param manager owning client session
         * @param collection physical field collection
         * @param definition JSON-object codec
         */
        private MongoPatchRepository(MongoRepositoryManager manager, MongoCollection<Document> collection,
                RepositoryDefinition<K, V> definition) {
            super(manager, collection, definition);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Optional<RepositorySnapshot<V>> findVersioned(K key) {
            checkOpen();
            String encoded = definition.encodeKey(key);
            try {
                Document record = collection.find(Filters.eq("_id", encoded)).first();
                if (record == null) {
                    return Optional.empty();
                }
                return Optional.of(new RepositorySnapshot<>(decode(encoded, record), revision(record)));
            } catch (RuntimeException failure) {
                throw new RepositoryException("Cannot load MongoDB field record " + encoded, failure);
            }
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
            Document record = new Document("_id", encoded)
                    .append("revision", UUID.randomUUID().toString())
                    .append("fields", new Document(fields));
            try {
                collection.replaceOne(Filters.eq("_id", encoded), record, new ReplaceOptions().upsert(true));
            } catch (RuntimeException failure) {
                throw new RepositoryException("Cannot save MongoDB field record " + encoded, failure);
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
            Document record;
            try {
                record = collection.find(Filters.eq("_id", encoded)).first();
            } catch (RuntimeException failure) {
                throw new RepositoryException("Cannot read MongoDB field record before patch " + encoded, failure);
            }
            if (record == null || !expectedRevision.equals(revision(record))) {
                throw new RepositoryConflictException(encoded);
            }
            Map<String, String> candidate = RepositoryFields.apply(fields(record), changes);
            V value = RepositoryFields.decode(definition, encoded, candidate);
            UUID revision = UUID.randomUUID();
            Document replacements = new Document("revision", revision.toString());
            Document removals = new Document();
            for (var entry : changes.entrySet()) {
                String path = "fields." + entry.getKey();
                if (entry.getValue() == null) {
                    removals.append(path, "");
                    continue;
                }
                replacements.append(path, entry.getValue());
            }
            Document update = new Document("$set", replacements);
            if (!removals.isEmpty()) {
                update.append("$unset", removals);
            }
            try {
                long matched = collection.updateOne(Filters.and(Filters.eq("_id", encoded),
                        Filters.eq("revision", expectedRevision.toString())), update).getMatchedCount();
                if (matched != 1) {
                    throw new RepositoryConflictException(encoded);
                }
            } catch (RepositoryConflictException conflict) {
                throw conflict;
            } catch (RuntimeException failure) {
                throw new RepositoryException("Cannot patch MongoDB field record " + encoded, failure);
            }
            return new RepositorySnapshot<>(value, revision);
        }

        /**
         * Decodes ordinary reads and cache refreshes using field fragments.
         *
         * @param encodedKey stored identity
         * @param record complete MongoDB document
         * @return decoded application value with matching key
         */
        @Override
        V decode(String encodedKey, Document record) {
            return RepositoryFields.decode(definition, encodedKey, fields(record));
        }

        /**
         * Requires the complete field map, preserving exact JSON numbers and explicit nulls.
         *
         * @param record physical MongoDB document
         * @return independent JSON fragment map
         */
        private static Map<String, String> fields(Document record) {
            if (!(record.get("fields") instanceof Document fields)) {
                throw new RepositoryException("Missing MongoDB repository field document");
            }
            Map<String, String> result = new Object2ObjectOpenHashMap<>();
            for (var entry : fields.entrySet()) {
                if (!(entry.getValue() instanceof String json)) {
                    throw new RepositoryException("Invalid MongoDB JSON fragment: " + entry.getKey());
                }
                result.put(entry.getKey(), json);
            }
            return result;
        }

        /**
         * Reads the record's opaque concurrency token.
         *
         * @param record physical MongoDB document
         * @return valid UUID write token
         */
        private static UUID revision(Document record) {
            try {
                return UUID.fromString(record.getString("revision"));
            } catch (RuntimeException failure) {
                throw new RepositoryException("Invalid MongoDB repository revision", failure);
            }
        }
    }
}
