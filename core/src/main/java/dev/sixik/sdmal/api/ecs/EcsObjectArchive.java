package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Comparator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Encodes immutable archive copies and stages objects before any lifecycle callbacks become
 * visible.
 */
final class EcsObjectArchive {
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
     * @param template   owned root tag
     * @param originals  owned object records in archive order
     * @param unresolved UUIDs reserved by opaque objects
     */
    private EcsObjectArchive(
            CompoundTag template, Map<UUID, CompoundTag> originals, Set<UUID> unresolved) {
        this.template = template;
        this.originals = originals;
        this.unresolved = it.unimi.dsi.fastutil.objects.ObjectSets.unmodifiable(new ObjectOpenHashSet<>(unresolved));
    }

    /**
     * @return fresh empty archive metadata
     */
    static EcsObjectArchive empty() {
        return new EcsObjectArchive(new CompoundTag(), new Object2ObjectLinkedOpenHashMap<>(), it.unimi.dsi.fastutil.objects.ObjectSets.emptySet());
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
     * @param object   fully decoded object without attachment callbacks
     * @param interval positive tick interval
     * @param delay    positive remaining dispatch delay
     * @param sleeping saved activity state
     */
    record Restored(EcsObject object, int interval, int delay, boolean sleeping) {
    }

    /**
     * Complete decoded archive; no manager has been changed yet.
     *
     * @param tick     saved manager sequence
     * @param objects  decoded objects in registration order
     * @param archive  retained opaque/template metadata
     * @param warnings diagnostics for objects retained without activation
     */
    record Decoded(
            long tick, List<Restored> objects, EcsObjectArchive archive, List<String> warnings) {
    }

    /**
     * Validates and stages an archive without changing the target manager. Unknown/failing
     * component types quarantine their entire object instead of running a partial object.
     *
     * @param input caller-owned archive, never mutated or retained directly
     * @return staged known objects, opaque records and diagnostics
     * @throws EcsPersistenceException if root structure or object identity is invalid
     */
    static Decoded decode(CompoundTag input) {
        CompoundTag root = input.copy();
        int format = integer(root, "format", 1, Integer.MAX_VALUE);
        if (format > FORMAT) throw new EcsPersistenceException.FutureFormat(format);
        long tick = sequence(root, "tick", Long.MAX_VALUE - Integer.MAX_VALUE);
        if (!(root.get("objects") instanceof ListTag records) || records.size() > MAX_OBJECTS)
            throw new EcsPersistenceException("Invalid ECS object list");
        Map<UUID, CompoundTag> originals = new Object2ObjectLinkedOpenHashMap<>();
        for (int index = 0; index < records.size(); index++) {
            if (!(records.get(index) instanceof CompoundTag record))
                throw new EcsPersistenceException("Object record must be a compound");
            UUID id = NbtAccess.uuid(record, "id", null);
            if (id == null || originals.putIfAbsent(id, record) != null)
                throw new EcsPersistenceException(
                        "Invalid or duplicate object UUID at index " + index);
        }
        List<Restored> loaded = new ObjectArrayList<>();
        List<String> warnings = new ObjectArrayList<>();
        Set<UUID> opaque = new ObjectOpenHashSet<>();
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
                ObjectArrayView.copyOf(loaded),
                new EcsObjectArchive(root, originals, opaque),
                ObjectArrayView.copyOf(warnings));
    }

    /**
     * Recreates one complete object before publishing any ownership callbacks.
     *
     * @param id     validated UUID
     * @param record owned object record
     * @return staged component container and scheduling metadata
     * @throws EcsPersistenceException if this object's schema cannot be recreated
     */
    private static Restored decodeObject(UUID id, CompoundTag record) {
        int interval = integer(record, "interval", 1, Integer.MAX_VALUE);
        int delay = integer(record, "delay", 1, Integer.MAX_VALUE);
        boolean sleeping = bool(record, "sleeping");
        if (!(record.get("components") instanceof ListTag components) || components.size() > 4096)
            throw new EcsPersistenceException("Invalid component list");
        List<EcsObject.ComponentEntry> entries = new ObjectArrayList<>();
        var slots = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        var counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String>();
        Map<Component, Boolean> instances = new Reference2ObjectOpenHashMap<>();
        var migrated = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<CompoundTag>();
        for (int index = 0; index < components.size(); index++) {
            if (!(components.get(index) instanceof CompoundTag component))
                throw new EcsPersistenceException("Component record must be a compound");
            String typeId = NbtAccess.string(component.get("type"), "");
            if (typeId.isBlank() || typeId.length() > 1024)
                throw new EcsPersistenceException("Invalid component type id");
            ComponentType<?> type =
                    ComponentRegistry.instance()
                            .get(typeId)
                            .orElseThrow(
                                    () ->
                                            new EcsPersistenceException(
                                                    "Missing component type " + typeId));
            int count = counts.addTo(typeId, 1) + 1;
            if (count > 1
                    && type.duplicatePolicy()
                    != ComponentType.DuplicatePolicy.ALLOW_MULTIPLE)
                throw new EcsPersistenceException(
                        "Duplicate non-repeatable component " + typeId);
            long slot = sequence(component, "slot", Long.MAX_VALUE - 1);
            if (!slots.add(slot))
                throw new EcsPersistenceException("Duplicate component slot " + slot);
            int version = integer(component, "version", 0, Integer.MAX_VALUE);
            if (!(component.get("data") instanceof CompoundTag data))
                throw new EcsPersistenceException("Missing component payload " + typeId);
            Component instance = type.createDefault();
            if (!type.componentClass().isInstance(instance)
                    || instance.isAttached()
                    || instances.put(instance, Boolean.TRUE) != null)
                throw new EcsPersistenceException(
                        "Factory must return a fresh detached component: " + typeId);
            int current = instance.dataVersion();
            if (current < 0 || version > current)
                throw new EcsPersistenceException(
                        "Unsupported component version " + typeId + ": " + version);
            CompoundTag payload = data.copy();
            if (version < current) {
                payload = java.util.Objects.requireNonNull(
                        instance.migrateNbt(version, payload), "migration payload");
                CompoundTag updated = component.copy();
                updated.put("data", payload.copy());
                updated.putInt("version", current);
                migrated.put(slot, updated);
            }
            instance.readNbt(payload);
            entries.add(new EcsObject.ComponentEntry(instance, type, slot));
        }
        EcsObject object = new EcsObject(id);
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
     * @param tick    current manager sequence
     * @return independent format-one archive
     * @throws EcsPersistenceException if a durable component cannot be reconstructed by its
     *                                         registry type
     */
    CompoundTag encode(List<EcsObject> objects, long tick) {
        CompoundTag root = template.copy();
        root.putInt("format", FORMAT);
        root.putLong("tick", tick);
        Map<UUID, EcsObject> live = new Object2ObjectLinkedOpenHashMap<>();
        for (EcsObject object : objects)
            if (object.isPersistent() && !object.isDetached()) live.put(object.id(), object);
        if (live.size() + unresolved.size() > MAX_OBJECTS)
            throw new EcsPersistenceException("Too many ECS objects");
        ListTag records = new ListTag();
        for (var entry : originals.entrySet()) {
            if (unresolved.contains(entry.getKey())) records.add(entry.getValue().copy());
            else {
                EcsObject object = live.remove(entry.getKey());
                if (object != null) records.add(encodeObject(object, tick, entry.getValue()));
            }
        }
        for (EcsObject object : live.values())
            records.add(encodeObject(object, tick, new CompoundTag()));
        root.put("objects", records);
        return root;
    }

    /**
     * Captures an externally persisted object regardless of its exclusion from the world archive.
     *
     * @param object live externally owned object
     * @param tick   current manager sequence
     * @return independent single-object archive preserving opaque extension fields
     */
    CompoundTag encodeHosted(EcsObject object, long tick) {
        CompoundTag root = template.copy();
        root.putInt("format", FORMAT);
        root.putLong("tick", tick);
        ListTag records = new ListTag();
        records.add(
                encodeObject(object, tick, originals.getOrDefault(object.id(), new CompoundTag())));
        root.put("objects", records);
        return root;
    }

    /**
     * Captures one object while retaining unrecognized component payload keys.
     *
     * @param object   durable live object
     * @param tick     manager sequence
     * @param original saved template or empty compound
     * @return independent object record
     * @throws EcsPersistenceException if a component is untyped or its type is not
     *                                         registered
     */
    private static CompoundTag encodeObject(EcsObject object, long tick, CompoundTag original) {
        CompoundTag record = original.copy();
        NbtAccess.putUuid(record, "id", object.id());
        record.putBoolean("sleeping", object.isSleeping());
        record.putInt("interval", object.tickInterval());
        record.putInt("delay", object.remainingTickDelay(tick));
        var templates = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<CompoundTag>();
        if (original.get("components") instanceof ListTag old) {
            for (var item : old)
                if (item instanceof CompoundTag component)
                    templates.put(sequence(component, "slot", Long.MAX_VALUE - 1), component);
        }
        List<EcsObject.ComponentEntry> entries = new ObjectArrayList<>(object.entries());
        entries.sort(Comparator.comparingLong(EcsObject.ComponentEntry::sequence));
        if (entries.size() > 4096)
            throw new EcsPersistenceException("Too many components on " + object.id());
        ListTag components = new ListTag();
        for (var entry : entries) {
            var type = entry.type();
            if (type == null
                    || ComponentRegistry.instance().get(type.id()).orElse(null) != type)
                throw new EcsPersistenceException(
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
            int version;
            try {
                version = entry.component().dataVersion();
                if (version < 0)
                    throw new EcsPersistenceException(
                            "Negative component data version " + type.id());
                entry.component().writeNbt(data);
            } catch (Throwable failure) {
                EcsFailures.rethrowFatal(failure);
                throw new EcsPersistenceException(
                        "Could not save component " + type.id() + " on " + object.id(), failure);
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
     * @throws EcsPersistenceException if the field is missing, not an int, or out of bounds
     */
    private static int integer(CompoundTag tag, String key, int min, int max) {
        if (!(tag.get(key) instanceof IntTag value))
            throw new EcsPersistenceException("Expected int: " + key);
        int number = NbtAccess.number(value).intValue();
        if (number < min || number > max)
            throw new EcsPersistenceException("Out of bounds: " + key);
        return number;
    }

    /**
     * Reads a non-negative bounded long sequence.
     *
     * @param tag containing compound
     * @param key required field name
     * @param max maximum inclusive value
     * @return validated sequence
     * @throws EcsPersistenceException for missing/invalid sequence data
     */
    private static long sequence(CompoundTag tag, String key, long max) {
        if (!(tag.get(key) instanceof LongTag value))
            throw new EcsPersistenceException("Expected long: " + key);
        long number = NbtAccess.number(value).longValue();
        if (number < 0 || number > max)
            throw new EcsPersistenceException("Out of bounds: " + key);
        return number;
    }

    /**
     * Reads an explicit zero/one byte flag.
     *
     * @param tag containing compound
     * @param key required flag name
     * @return decoded boolean
     * @throws EcsPersistenceException if the byte is absent or not zero/one
     */
    private static boolean bool(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ByteTag value))
            throw new EcsPersistenceException("Expected boolean: " + key);
        byte number = NbtAccess.number(value).byteValue();
        if (number != 0 && number != 1)
            throw new EcsPersistenceException("Invalid boolean: " + key);
        return number == 1;
    }
}
