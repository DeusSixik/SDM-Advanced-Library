package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Encodes immutable archive copies and stages objects before any lifecycle callbacks become
 * visible.
 */
final class WorldObjectArchive {
    /**
     * First public on-disk format, independent of Minecraft's data version.
     */
    static final int FORMAT = 1;

    /**
     * Maximum object records accepted from one archive.
     */
    private static final int MAX_OBJECTS = 100_000;

    /**
     * Original root, including unknown extension fields. Never mutated after construction.
     */
    private final CompoundTag template;

    /**
     * Original object records in archive order, including unresolved objects.
     */
    private final Map<UUID, CompoundTag> originals;

    /**
     * Objects kept as opaque records because their complete component set cannot be restored.
     */
    private final Set<UUID> unresolved;

    /**
     * Retains an independent archive template.
     *
     * @param template owned root tag
     * @param originals owned object records in archive order
     * @param unresolved UUIDs reserved by opaque objects
     */
    private WorldObjectArchive(
            CompoundTag template, Map<UUID, CompoundTag> originals, Set<UUID> unresolved) {
        this.template = template;
        this.originals = originals;
        this.unresolved = Set.copyOf(unresolved);
    }

    /**
     * @return fresh empty archive metadata
     */
    static WorldObjectArchive empty() {
        return new WorldObjectArchive(new CompoundTag(), new LinkedHashMap<>(), Set.of());
    }

    /**
     * @return reserved UUIDs of unresolved objects; immutable
     */
    Set<UUID> unresolved() {
        return unresolved;
    }

    /**
     * Staged object plus scheduling data which must be installed after manager ownership.
     *
     * @param object fully decoded object without attachment callbacks
     * @param interval positive tick interval
     * @param delay positive remaining dispatch delay
     * @param sleeping saved activity state
     */
    record Restored(WorldObject object, int interval, int delay, boolean sleeping) {}

    /**
     * Complete decoded archive; no manager has been changed yet.
     *
     * @param tick saved manager sequence
     * @param objects decoded objects in registration order
     * @param archive retained opaque/template metadata
     * @param warnings diagnostics for objects retained without activation
     */
    record Decoded(
            long tick, List<Restored> objects, WorldObjectArchive archive, List<String> warnings) {}

    /**
     * Validates and stages an archive without changing the target manager. Unknown/failing
     * component types quarantine their entire object instead of running a partial object.
     *
     * @param input caller-owned archive, never mutated or retained directly
     * @return staged known objects, opaque records and diagnostics
     * @throws WorldObjectPersistenceException if root structure or object identity is invalid
     */
    static Decoded decode(CompoundTag input) {
        CompoundTag root = input.copy();
        int format = integer(root, "format", 1, Integer.MAX_VALUE);
        if (format > FORMAT) throw new WorldObjectPersistenceException.FutureFormat(format);
        long tick = sequence(root, "tick", Long.MAX_VALUE - Integer.MAX_VALUE);
        if (!(root.get("objects") instanceof ListTag records) || records.size() > MAX_OBJECTS)
            throw new WorldObjectPersistenceException("Invalid ECS object list");
        Map<UUID, CompoundTag> originals = new LinkedHashMap<>();
        for (int index = 0; index < records.size(); index++) {
            if (!(records.get(index) instanceof CompoundTag record))
                throw new WorldObjectPersistenceException("Object record must be a compound");
            UUID id = NbtAccess.uuid(record, "id", null);
            if (id == null || originals.putIfAbsent(id, record) != null)
                throw new WorldObjectPersistenceException(
                        "Invalid or duplicate object UUID at index " + index);
        }
        List<Restored> loaded = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<UUID> opaque = new HashSet<>();
        for (var entry : originals.entrySet()) {
            try {
                loaded.add(decodeObject(entry.getKey(), entry.getValue()));
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                opaque.add(entry.getKey());
                warnings.add(
                        entry.getKey()
                                + ": "
                                + failure.getClass().getSimpleName()
                                + ": "
                                + failure.getMessage());
            }
        }
        return new Decoded(
                tick,
                List.copyOf(loaded),
                new WorldObjectArchive(root, originals, opaque),
                List.copyOf(warnings));
    }

    /**
     * Recreates one complete object before publishing any ownership callbacks.
     *
     * @param id validated UUID
     * @param record owned object record
     * @return staged component container and scheduling metadata
     * @throws WorldObjectPersistenceException if this object's schema cannot be recreated
     */
    private static Restored decodeObject(UUID id, CompoundTag record) {
        int interval = integer(record, "interval", 1, Integer.MAX_VALUE);
        int delay = integer(record, "delay", 1, Integer.MAX_VALUE);
        boolean sleeping = bool(record, "sleeping");
        if (!(record.get("components") instanceof ListTag components) || components.size() > 4096)
            throw new WorldObjectPersistenceException("Invalid component list");
        List<WorldObject.ComponentEntry> entries = new ArrayList<>();
        Set<Long> slots = new HashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<WorldObjectComponent, Boolean> instances = new IdentityHashMap<>();
        Map<Long, CompoundTag> migrated = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            if (!(components.get(index) instanceof CompoundTag component))
                throw new WorldObjectPersistenceException("Component record must be a compound");
            String typeId = NbtAccess.string(component.get("type"), "");
            if (typeId.isBlank() || typeId.length() > 1024)
                throw new WorldObjectPersistenceException("Invalid component type id");
            WorldObjectComponentType<?> type =
                    WorldObjectComponentRegistry.instance()
                            .get(typeId)
                            .orElseThrow(
                                    () ->
                                            new WorldObjectPersistenceException(
                                                    "Missing component type " + typeId));
            int count = counts.merge(typeId, 1, Integer::sum);
            if (count > 1
                    && type.duplicatePolicy()
                            != WorldObjectComponentType.DuplicatePolicy.ALLOW_MULTIPLE)
                throw new WorldObjectPersistenceException(
                        "Duplicate non-repeatable component " + typeId);
            long slot = sequence(component, "slot", Long.MAX_VALUE - 1);
            if (!slots.add(slot))
                throw new WorldObjectPersistenceException("Duplicate component slot " + slot);
            int version = integer(component, "version", 0, Integer.MAX_VALUE);
            if (!(component.get("data") instanceof CompoundTag data))
                throw new WorldObjectPersistenceException("Missing component payload " + typeId);
            WorldObjectComponent instance = type.createDefault();
            if (!(instance instanceof AbstractWorldObjectComponent)
                    || !type.componentClass().isInstance(instance)
                    || instance.isAttached()
                    || instances.put(instance, Boolean.TRUE) != null)
                throw new WorldObjectPersistenceException(
                        "Factory must return a fresh detached component: " + typeId);
            if (instance instanceof PersistentWorldObjectComponent persistent) {
                int current = persistent.dataVersion();
                if (current < 0 || version > current)
                    throw new WorldObjectPersistenceException(
                            "Unsupported component version " + typeId + ": " + version);
                CompoundTag payload = data.copy();
                if (version < current) {
                    payload =
                            java.util.Objects.requireNonNull(
                                    persistent.migrateNbt(version, payload), "migration payload");
                    CompoundTag updated = component.copy();
                    updated.put("data", payload.copy());
                    updated.putInt("version", current);
                    migrated.put(slot, updated);
                }
                persistent.readNbt(payload);
            } else if (version != 0) {
                throw new WorldObjectPersistenceException(
                        "Component no longer supports saved state: " + typeId);
            }
            entries.add(new WorldObject.ComponentEntry(instance, type, slot));
        }
        WorldObject object = new WorldObject(id);
        object.prepareRestoredComponents(entries);
        for (int index = 0; index < components.size(); index++) {
            CompoundTag component = (CompoundTag) components.get(index);
            CompoundTag updated = migrated.get(sequence(component, "slot", Long.MAX_VALUE - 1));
            if (updated != null) components.set(index, updated);
        }
        return new Restored(object, interval, delay, sleeping);
    }

    /**
     * Captures current durable objects and preserves opaque records and unknown extension fields.
     *
     * @param objects stable manager membership snapshot
     * @param tick current manager sequence
     * @return independent format-one archive
     * @throws WorldObjectPersistenceException if a durable component cannot be reconstructed by its
     *     registry type
     */
    CompoundTag encode(List<WorldObject> objects, long tick) {
        CompoundTag root = template.copy();
        root.putInt("format", FORMAT);
        root.putLong("tick", tick);
        Map<UUID, WorldObject> live = new LinkedHashMap<>();
        for (WorldObject object : objects)
            if (object.isPersistent() && !object.isDetached()) live.put(object.id(), object);
        if (live.size() + unresolved.size() > MAX_OBJECTS)
            throw new WorldObjectPersistenceException("Too many ECS objects");
        ListTag records = new ListTag();
        for (var entry : originals.entrySet()) {
            if (unresolved.contains(entry.getKey())) records.add(entry.getValue().copy());
            else {
                WorldObject object = live.remove(entry.getKey());
                if (object != null) records.add(encodeObject(object, tick, entry.getValue()));
            }
        }
        for (WorldObject object : live.values())
            records.add(encodeObject(object, tick, new CompoundTag()));
        root.put("objects", records);
        return root;
    }

    /**
     * Captures one object while retaining unrecognized component payload keys.
     *
     * @param object durable live object
     * @param tick manager sequence
     * @param original saved template or empty compound
     * @return independent object record
     * @throws WorldObjectPersistenceException if a component is untyped or its type is not
     *     registered
     */
    private static CompoundTag encodeObject(WorldObject object, long tick, CompoundTag original) {
        CompoundTag record = original.copy();
        NbtAccess.putUuid(record, "id", object.id());
        record.putBoolean("sleeping", object.isSleeping());
        record.putInt("interval", object.tickInterval());
        record.putInt("delay", object.remainingTickDelay(tick));
        Map<Long, CompoundTag> templates = new LinkedHashMap<>();
        if (original.get("components") instanceof ListTag old) {
            for (var item : old)
                if (item instanceof CompoundTag component)
                    templates.put(sequence(component, "slot", Long.MAX_VALUE - 1), component);
        }
        List<WorldObject.ComponentEntry> entries = new ArrayList<>(object.entries());
        entries.sort(Comparator.comparingLong(WorldObject.ComponentEntry::sequence));
        if (entries.size() > 4096)
            throw new WorldObjectPersistenceException("Too many components on " + object.id());
        ListTag components = new ListTag();
        for (var entry : entries) {
            var type = entry.type();
            if (type == null
                    || WorldObjectComponentRegistry.instance().get(type.id()).orElse(null) != type)
                throw new WorldObjectPersistenceException(
                        "Durable object "
                                + object.id()
                                + " has an untyped/unregistered component; register its type or"
                                + " setPersistent(false)");
            CompoundTag saved =
                    templates.containsKey(entry.sequence())
                            ? templates.get(entry.sequence()).copy()
                            : new CompoundTag();
            CompoundTag data =
                    saved.get("data") instanceof CompoundTag previous
                            ? previous.copy()
                            : new CompoundTag();
            int version = 0;
            if (entry.component() instanceof PersistentWorldObjectComponent persistent) {
                try {
                    version = persistent.dataVersion();
                    if (version < 0)
                        throw new WorldObjectPersistenceException(
                                "Negative component data version " + type.id());
                    persistent.writeNbt(data);
                } catch (Throwable failure) {
                    EcsFailures.rethrowFatal(failure);
                    throw new WorldObjectPersistenceException(
                            "Could not save component " + type.id() + " on " + object.id(),
                            failure);
                }
            }
            saved.putString("type", type.id());
            saved.putLong("slot", entry.sequence());
            saved.putInt("version", version);
            saved.put("data", data);
            components.add(saved);
        }
        record.put("components", components);
        return record;
    }

    /**
     * Reads a bounded integral schema field without optional/version-specific accessors.
     *
     * @param tag containing compound
     * @param key required field name
     * @param min minimum inclusive value
     * @param max maximum inclusive value
     * @return validated integer
     * @throws WorldObjectPersistenceException if the field is missing, not an int, or out of bounds
     */
    private static int integer(CompoundTag tag, String key, int min, int max) {
        if (!(tag.get(key) instanceof IntTag value))
            throw new WorldObjectPersistenceException("Expected int: " + key);
        int number = NbtAccess.number(value).intValue();
        if (number < min || number > max)
            throw new WorldObjectPersistenceException("Out of bounds: " + key);
        return number;
    }

    /**
     * Reads a non-negative bounded long sequence.
     *
     * @param tag containing compound
     * @param key required field name
     * @param max maximum inclusive value
     * @return validated sequence
     * @throws WorldObjectPersistenceException for missing/invalid sequence data
     */
    private static long sequence(CompoundTag tag, String key, long max) {
        if (!(tag.get(key) instanceof LongTag value))
            throw new WorldObjectPersistenceException("Expected long: " + key);
        long number = NbtAccess.number(value).longValue();
        if (number < 0 || number > max)
            throw new WorldObjectPersistenceException("Out of bounds: " + key);
        return number;
    }

    /**
     * Reads an explicit zero/one byte flag.
     *
     * @param tag containing compound
     * @param key required flag name
     * @return decoded boolean
     * @throws WorldObjectPersistenceException if the byte is absent or not zero/one
     */
    private static boolean bool(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ByteTag value))
            throw new WorldObjectPersistenceException("Expected boolean: " + key);
        byte number = NbtAccess.number(value).byteValue();
        if (number != 0 && number != 1)
            throw new WorldObjectPersistenceException("Invalid boolean: " + key);
        return number == 1;
    }
}
