package dev.sixik.sdmal.api.ecs.sync;

import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Built-in {@link FieldCodec} instances and combinators.
 *
 * <p>All codecs are stateless singletons (except parameterized {@link #listOf} / {@link #mapOf} /
 * {@link #enumCodec} results) and safe to share across threads. Every schema name is stable across
 * restarts; renaming one breaks network compatibility by design, because {@code
 * SyncedWorldObjectComponent} compares schemas on delta reads.
 *
 * <p>Copy semantics: immutable values return as-is; {@link #listOf}, {@link #mapOf} and {@link
 * #compoundOf} deep-copy through their element codecs so snapshots never alias live state.
 */
public final class FieldCodecs {

    /**
     * Creates a FieldCodecs with the supplied configuration.
     */
    private FieldCodecs() {}

    /**
     * Boolean codec.
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<Boolean> bool() {
        return BoolCodec.INSTANCE;
    }

    /**
     * Byte codec.
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<Byte> byteValue() {
        return ByteCodec.INSTANCE;
    }

    /**
     * Int codec (NBT int, network var-int).
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<Integer> intValue() {
        return IntCodec.INSTANCE;
    }

    /**
     * Long codec (NBT long, network var-long).
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<Long> longValue() {
        return LongCodec.INSTANCE;
    }

    /**
     * Float codec.
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<Float> floatValue() {
        return FloatCodec.INSTANCE;
    }

    /**
     * Double codec.
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<Double> doubleValue() {
        return DoubleCodec.INSTANCE;
    }

    /**
     * UTF-8 string codec (network length capped at 32767, vanilla limit).
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<String> string() {
        return StringCodec.INSTANCE;
    }

    /**
     * UUID codec (two longs on the wire, int-array in NBT).
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<UUID> uuid() {
        return UuidCodec.INSTANCE;
    }

    /**
     * Raw {@link CompoundTag} codec. Values are deep-copied on every snapshot.
     *
     * @return codec defining the declared value representation, never null
     */
    public static FieldCodec<CompoundTag> compound() {
        return CompoundCodec.INSTANCE;
    }

    /**
     * Optional wrapper: presence flag plus the nested value. A {@code null} value inside {@link
     * TrackedField} means "absent".
     *
     * @param <Value> field or callback value type
     * @param element nested value codec or NBT element as required by this method
     * @return codec defining the declared value representation, never null
     */
    public static <Value> FieldCodec<Optional<Value>> optionalOf(FieldCodec<Value> element) {
        Objects.requireNonNull(element, "element");
        return FieldCodec.<Optional<Value>>builder()
                .schema("optional<" + element.schemaName() + ">")
                .nbt(
                        (nbt, key, value) -> {
                            CompoundTag wrapper = new CompoundTag();
                            boolean present = value != null && value.isPresent();
                            wrapper.putBoolean("$present", present);
                            if (present) wrapper.put("v", element.toNbtElement(value.get()));
                            nbt.put(key, wrapper);
                        },
                        (nbt, key, defaultValue) -> {
                            Tag child = nbt.get(key);
                            if (child == null) {
                                return defaultValue;
                            }
                            if (child instanceof CompoundTag wrapper
                                    && wrapper.get("$present") instanceof NumericTag present) {
                                return NbtAccess.number(present).byteValue() == 0
                                        ? Optional.empty()
                                        : Optional.ofNullable(
                                                element.fromNbtElement(wrapper.get("v"), null));
                            }
                            return Optional.ofNullable(element.fromNbtElement(child, null));
                        })
                .nbtElement(
                        value -> {
                            CompoundTag wrapper = new CompoundTag();
                            if (value != null && value.isPresent()) {
                                wrapper.put("v", element.toNbtElement(value.get()));
                            }
                            return wrapper;
                        },
                        (tag, defaultValue) -> {
                            if (!(tag instanceof CompoundTag wrapper)) {
                                return defaultValue;
                            }
                            Tag child = wrapper.get("v");
                            if (child == null) {
                                return Optional.empty();
                            }
                            return Optional.ofNullable(element.fromNbtElement(child, null));
                        })
                .network(
                        (buf, value) -> {
                            boolean present = value != null && value.isPresent();
                            buf.writeBoolean(present);
                            if (present) {
                                element.toNetwork(buf, value.get());
                            }
                        },
                        buf ->
                                buf.readBoolean()
                                        ? Optional.of(element.fromNetwork(buf))
                                        : Optional.empty())
                .copy(
                        value -> {
                            if (value == null || value.isEmpty()) {
                                return value;
                            }
                            return Optional.of(element.copy(value.get()));
                        })
                .equality(
                        (first, second) -> {
                            Object a = first == null ? null : first.orElse(null);
                            Object b = second == null ? null : second.orElse(null);
                            return element.areEqual(
                                    uncheckedCast(a, element), uncheckedCast(b, element));
                        })
                .build();
    }

    /**
     * List codec over fastutil {@link ObjectList}: defensive copy on snapshot, order-preserving,
     * element-wise equality. All decoded/copied values are {@link ObjectArrayList} instances — no
     * JDK boxing lists on the hot path.
     *
     * @param <Value> field or callback value type
     * @param element nested value codec or NBT element as required by this method
     * @return codec defining the declared value representation, never null
     */
    public static <Value> FieldCodec<ObjectList<Value>> listOf(FieldCodec<Value> element) {
        Objects.requireNonNull(element, "element");
        return FieldCodec.<ObjectList<Value>>builder()
                .schema("list<" + element.schemaName() + ">")
                .nbt(
                        (nbt, key, value) -> {
                            ListTag list = new ListTag();
                            if (value != null) {
                                for (Value item : value) {
                                    list.add(element.toNbtElement(item));
                                }
                            }
                            nbt.put(key, list);
                        },
                        (nbt, key, defaultValue) -> {
                            if (!(nbt.get(key) instanceof ListTag list)) {
                                return defaultValue;
                            }
                            ObjectList<Value> result = new ObjectArrayList<>(list.size());
                            for (Tag child : list) {
                                result.add(element.fromNbtElement(child, null));
                            }
                            return result;
                        })
                .nbtElement(
                        value -> {
                            ListTag list = new ListTag();
                            if (value != null) {
                                for (Value item : value) {
                                    list.add(element.toNbtElement(item));
                                }
                            }
                            return list;
                        },
                        (tag, defaultValue) -> {
                            if (!(tag instanceof ListTag list)) {
                                return defaultValue;
                            }
                            ObjectList<Value> result = new ObjectArrayList<>(list.size());
                            for (Tag child : list) {
                                result.add(element.fromNbtElement(child, null));
                            }
                            return result;
                        })
                .network(
                        (buf, value) -> {
                            ObjectList<Value> safe =
                                    value == null ? ObjectLists.emptyList() : value;
                            buf.writeVarInt(safe.size());
                            for (Value item : safe) {
                                element.toNetwork(buf, item);
                            }
                        },
                        buf -> {
                            int size = buf.readVarInt();
                            if (size < 0 || size > 65536) {
                                throw new SyncException("List size out of bounds: " + size);
                            }
                            ObjectList<Value> result = new ObjectArrayList<>(size);
                            for (int i = 0; i < size; i++) {
                                result.add(element.fromNetwork(buf));
                            }
                            return result;
                        })
                .copy(
                        value -> {
                            if (value == null) {
                                return null;
                            }
                            ObjectList<Value> copy = new ObjectArrayList<>(value.size());
                            for (Value item : value) {
                                copy.add(element.copy(item));
                            }
                            return copy;
                        })
                .equality(
                        (first, second) -> {
                            if (first == second) {
                                return true;
                            }
                            if (first == null || second == null || first.size() != second.size()) {
                                return false;
                            }
                            for (int i = 0; i < first.size(); i++) {
                                if (!element.areEqual(first.get(i), second.get(i))) {
                                    return false;
                                }
                            }
                            return true;
                        })
                .build();
    }

    /**
     * Map codec over fastutil {@link Object2ObjectMap} with a string-key codec and arbitrary value
     * codec. All decoded/copied values are {@link Object2ObjectOpenHashMap} instances.
     *
     * <p>NBT: compound, keys must survive {@code keyCodec.toNbtElement/fromNbtElement} round-trip
     * (string keys natively, other key types via their element form). Network: var-int size, then
     * key+value pairs. Copy is deep on both sides, equality is order-independent and element-wise.
     * Size caps mirror {@link #listOf}: 65536 entries max on decode.
     *
     * @param <Key> field or callback value type
     * @param <Value> field or callback value type
     * @param keyCodec codec for map keys; NBT keys must encode to StringTag
     * @param valueCodec codec for map values, non-null
     * @return codec defining the declared value representation, never null
     */
    public static <Key, Value> FieldCodec<Object2ObjectMap<Key, Value>> mapOf(
            FieldCodec<Key> keyCodec, FieldCodec<Value> valueCodec) {
        Objects.requireNonNull(keyCodec, "keyCodec");
        Objects.requireNonNull(valueCodec, "valueCodec");
        return FieldCodec.<Object2ObjectMap<Key, Value>>builder()
                .schema("map<" + keyCodec.schemaName() + "," + valueCodec.schemaName() + ">")
                .nbt(
                        (nbt, key, value) -> {
                            CompoundTag child = new CompoundTag();
                            if (value != null) {
                                for (Object2ObjectMap.Entry<Key, Value> entry :
                                        value.object2ObjectEntrySet()) {
                                    Tag encodedKey = keyCodec.toNbtElement(entry.getKey());
                                    if (!(encodedKey instanceof StringTag text)) {
                                        throw new SyncException(
                                                "Map NBT keys must encode to StringTag, got "
                                                        + encodedKey.getClass().getSimpleName());
                                    }
                                    child.put(
                                            NbtAccess.string(text),
                                            valueCodec.toNbtElement(entry.getValue()));
                                }
                            }
                            nbt.put(key, child);
                        },
                        (nbt, key, defaultValue) -> {
                            if (!(nbt.get(key) instanceof CompoundTag child)) {
                                return defaultValue;
                            }
                            return readMapEntries(child, keyCodec, valueCodec);
                        })
                .nbtElement(
                        value -> {
                            CompoundTag child = new CompoundTag();
                            if (value != null) {
                                for (Object2ObjectMap.Entry<Key, Value> entry :
                                        value.object2ObjectEntrySet()) {
                                    Tag encodedKey = keyCodec.toNbtElement(entry.getKey());
                                    if (!(encodedKey instanceof StringTag text)) {
                                        throw new SyncException(
                                                "Map NBT keys must encode to StringTag, got "
                                                        + encodedKey.getClass().getSimpleName());
                                    }
                                    child.put(
                                            NbtAccess.string(text),
                                            valueCodec.toNbtElement(entry.getValue()));
                                }
                            }
                            return child;
                        },
                        (tag, defaultValue) ->
                                tag instanceof CompoundTag child
                                        ? readMapEntries(child, keyCodec, valueCodec)
                                        : defaultValue)
                .network(
                        (buf, value) -> {
                            Object2ObjectMap<Key, Value> safe =
                                    value == null ? Object2ObjectMaps.emptyMap() : value;
                            buf.writeVarInt(safe.size());
                            for (Object2ObjectMap.Entry<Key, Value> entry :
                                    safe.object2ObjectEntrySet()) {
                                keyCodec.toNetwork(buf, entry.getKey());
                                valueCodec.toNetwork(buf, entry.getValue());
                            }
                        },
                        buf -> {
                            int size = buf.readVarInt();
                            if (size < 0 || size > 65536) {
                                throw new SyncException("Map size out of bounds: " + size);
                            }
                            Object2ObjectMap<Key, Value> result =
                                    new Object2ObjectOpenHashMap<>(size);
                            for (int i = 0; i < size; i++) {
                                result.put(keyCodec.fromNetwork(buf), valueCodec.fromNetwork(buf));
                            }
                            return result;
                        })
                .copy(
                        value -> {
                            if (value == null) {
                                return null;
                            }
                            Object2ObjectMap<Key, Value> copy =
                                    new Object2ObjectOpenHashMap<>(value.size());
                            for (Object2ObjectMap.Entry<Key, Value> entry :
                                    value.object2ObjectEntrySet()) {
                                copy.put(
                                        keyCodec.copy(entry.getKey()),
                                        valueCodec.copy(entry.getValue()));
                            }
                            return copy;
                        })
                .equality(
                        (first, second) -> {
                            if (first == second) {
                                return true;
                            }
                            if (first == null || second == null || first.size() != second.size()) {
                                return false;
                            }
                            for (Object2ObjectMap.Entry<Key, Value> entry :
                                    first.object2ObjectEntrySet()) {
                                if (!second.containsKey(entry.getKey())) {
                                    return false;
                                }
                                if (!valueCodec.areEqual(
                                        entry.getValue(), second.get(entry.getKey()))) {
                                    return false;
                                }
                            }
                            return true;
                        })
                .build();
    }

    /**
     * Decodes compound entries through the declared key and value codecs.
     *
     * @param <Key> field or callback value type
     * @param <Value> field or callback value type
     * @param child nested NBT compound containing encoded map entries
     * @param keyCodec codec for map keys; NBT keys must encode to StringTag
     * @param valueCodec codec for map values, non-null
     * @return new map of decoded entries, never null
     */
    private static <Key, Value> Object2ObjectMap<Key, Value> readMapEntries(
            CompoundTag child, FieldCodec<Key> keyCodec, FieldCodec<Value> valueCodec) {
        Object2ObjectMap<Key, Value> result = new Object2ObjectOpenHashMap<>();
        for (String encodedKey : NbtAccess.keys(child)) {
            Key key = keyCodec.fromNbtElement(StringTag.valueOf(encodedKey), null);
            if (key == null) {
                continue;
            }
            result.put(key, valueCodec.fromNbtElement(child.get(encodedKey), null));
        }
        return result;
    }

    /**
     * Enum codec by stable {@link Enum#name}. Unknown names on read fall back to {@code
     * defaultValue} instead of throwing, to tolerate content removals.
     *
     * @param <E> field or callback value type
     * @param enumClass enum class whose constant names define the serialized values
     * @return codec defining the declared value representation, never null
     */
    public static <E extends Enum<E>> FieldCodec<E> enumCodec(Class<E> enumClass) {
        Objects.requireNonNull(enumClass, "enumClass");
        E[] constants = enumClass.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalArgumentException(enumClass.getName() + " is not an enum");
        }
        return FieldCodec.<E>builder()
                .schema("enum:" + enumClass.getName())
                .nbt(
                        (nbt, key, value) -> {
                            if (value != null) {
                                nbt.putString(key, value.name());
                            } else nbt.remove(key);
                        },
                        (nbt, key, defaultValue) -> {
                            String name = NbtAccess.string(nbt.get(key), "");
                            if (name == null || name.isEmpty()) {
                                return defaultValue;
                            }
                            try {
                                return Enum.valueOf(enumClass, name);
                            } catch (IllegalArgumentException unknown) {
                                return defaultValue;
                            }
                        })
                .nbtElement(
                        value -> StringTag.valueOf(value == null ? "" : value.name()),
                        (tag, defaultValue) -> {
                            if (!(tag instanceof StringTag named)
                                    || NbtAccess.string(named).isEmpty()) {
                                return defaultValue;
                            }
                            try {
                                return Enum.valueOf(enumClass, NbtAccess.string(named));
                            } catch (IllegalArgumentException unknown) {
                                return defaultValue;
                            }
                        })
                .network(
                        (buf, value) -> buf.writeUtf(value == null ? "" : value.name(), 128),
                        buf -> {
                            String name = buf.readUtf(128);
                            if (name.isEmpty()) {
                                return null;
                            }
                            try {
                                return Enum.valueOf(enumClass, name);
                            } catch (IllegalArgumentException unknown) {
                                throw new SyncException(
                                        "Unknown " + enumClass.getSimpleName() + ": " + name);
                            }
                        })
                .build();
    }

    /**
     * Nested compound codec backed by {@link CompoundFieldWriter}/{@link CompoundFieldReader}
     * lambdas. The whole compound is one {@link TrackedField}, therefore the writer must emit a
     * fully consistent snapshot of its inputs.
     *
     * @param <Value> field or callback value type
     * @param schemaName stable codec identifier used in schema fingerprints
     * @param writer function encoding a value in the selected representation
     * @param reader function decoding a value from the selected representation
     * @param copier defensive copying function; null selects identity copying
     * @param equality value comparison function; null selects Objects.equals
     * @return codec defining the declared value representation, never null
     */
    public static <Value> FieldCodec<Value> compoundOf(
            String schemaName,
            CompoundFieldWriter<Value> writer,
            CompoundFieldReader<Value> reader,
            FieldCodec.ValueCopier<Value> copier,
            FieldCodec.ValueEquality<Value> equality) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(reader, "reader");
        return FieldCodec.<Value>builder()
                .schema(schemaName == null ? "compound" : schemaName)
                .nbt(
                        (nbt, key, value) -> {
                            CompoundTag child = new CompoundTag();
                            if (value != null) {
                                writer.write(child, value);
                            }
                            nbt.put(key, child);
                        },
                        (nbt, key, defaultValue) -> {
                            if (!(nbt.get(key) instanceof CompoundTag child)) {
                                return defaultValue;
                            }
                            return reader.read(child, defaultValue);
                        })
                .nbtElement(
                        value -> {
                            CompoundTag child = new CompoundTag();
                            if (value != null) {
                                writer.write(child, value);
                            }
                            return child;
                        },
                        (tag, defaultValue) ->
                                tag instanceof CompoundTag child
                                        ? reader.read(child, defaultValue)
                                        : defaultValue)
                .network(
                        (buf, value) -> buf.writeNbt(compoundWriterToNbt(value, writer)),
                        buf -> reader.read(buf.readNbt(), null))
                .copy(copier == null ? value -> value : copier)
                .equality(equality == null ? Objects::equals : equality)
                .build();
    }

    /**
     * Writes fields of a nested object into a compound.
     */
    @FunctionalInterface
    public interface CompoundFieldWriter<Value> {
        /**
         * Encodes one value using the declared representation.
         *
         * @param nbt source or destination NBT compound, non-null
         * @param value field value; null handling is defined by the codec
         */
        void write(CompoundTag nbt, Value value);
    }

    /**
     * Reads a nested object from a compound; {@code defaultValue} is the field default.
     */
    @FunctionalInterface
    public interface CompoundFieldReader<Value> {
        /**
         * Decodes one value using the declared representation.
         *
         * @param nbt source or destination NBT compound, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded field value; null handling follows the codec contract
         */
        Value read(CompoundTag nbt, Value defaultValue);
    }

    /**
     * Restores the generic value type established by the caller's codec.
     *
     * @param <Value> field or callback value type
     * @param value field value; null handling is defined by the codec
     * @param codec non-null codec defining persistence, copy and equality semantics
     * @return same value with its declared generic type, possibly null
     */
    @SuppressWarnings("unchecked")
    private static <Value> Value uncheckedCast(Object value, FieldCodec<Value> codec) {
        return (Value) value;
    }

    /**
     * Writes a nested value into a newly allocated compound.
     *
     * @param <Value> field or callback value type
     * @param value field value; null handling is defined by the codec
     * @param writer function encoding a value in the selected representation
     * @return new compound populated by the supplied writer
     */
    private static <Value> CompoundTag compoundWriterToNbt(
            Value value, CompoundFieldWriter<Value> writer) {
        CompoundTag child = new CompoundTag();
        if (value != null) {
            writer.write(child, value);
        }
        return child;
    }

    /**
     * Boolean values as numeric NBT and one network boolean.
     */
    private enum BoolCodec implements FieldCodec<Boolean> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, Boolean value) {
            nbt.putBoolean(key, value != null && value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Boolean fromNbt(CompoundTag nbt, String key, Boolean defaultValue) {
            return nbt.get(key) instanceof NumericTag number
                    ? NbtAccess.number(number).byteValue() != 0
                    : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(Boolean value) {
            return ByteTag.valueOf((byte) (Boolean.TRUE.equals(value) ? 1 : 0));
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Boolean fromNbtElement(Tag element, Boolean defaultValue) {
            if (element instanceof ByteTag numeric) {
                return NbtAccess.number(numeric).byteValue() != 0;
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, Boolean value) {
            buf.writeBoolean(Boolean.TRUE.equals(value));
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public Boolean fromNetwork(FriendlyByteBuf buf) {
            return buf.readBoolean();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "bool";
        }
    }

    /**
     * Signed 8-bit values in NBT and network buffers.
     */
    private enum ByteCodec implements FieldCodec<Byte> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, Byte value) {
            nbt.putByte(key, value == null ? 0 : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Byte fromNbt(CompoundTag nbt, String key, Byte defaultValue) {
            return nbt.get(key) instanceof NumericTag number
                    ? NbtAccess.number(number).byteValue()
                    : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(Byte value) {
            return ByteTag.valueOf(value == null ? 0 : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Byte fromNbtElement(Tag element, Byte defaultValue) {
            if (element instanceof ByteTag numeric) {
                return NbtAccess.number(numeric).byteValue();
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, Byte value) {
            buf.writeByte(value == null ? 0 : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public Byte fromNetwork(FriendlyByteBuf buf) {
            return buf.readByte();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "byte";
        }
    }

    /**
     * Signed 32-bit values with variable-length network encoding.
     */
    private enum IntCodec implements FieldCodec<Integer> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, Integer value) {
            nbt.putInt(key, value == null ? 0 : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Integer fromNbt(CompoundTag nbt, String key, Integer defaultValue) {
            return nbt.get(key) instanceof NumericTag number
                    ? NbtAccess.number(number).intValue()
                    : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(Integer value) {
            return IntTag.valueOf(value == null ? 0 : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Integer fromNbtElement(Tag element, Integer defaultValue) {
            if (element instanceof IntTag numeric) {
                return NbtAccess.number(numeric).intValue();
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, Integer value) {
            buf.writeVarInt(value == null ? 0 : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public Integer fromNetwork(FriendlyByteBuf buf) {
            return buf.readVarInt();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "int";
        }
    }

    /**
     * Signed 64-bit values with variable-length network encoding.
     */
    private enum LongCodec implements FieldCodec<Long> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, Long value) {
            nbt.putLong(key, value == null ? 0L : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Long fromNbt(CompoundTag nbt, String key, Long defaultValue) {
            return nbt.get(key) instanceof NumericTag number
                    ? NbtAccess.number(number).longValue()
                    : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(Long value) {
            return LongTag.valueOf(value == null ? 0L : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Long fromNbtElement(Tag element, Long defaultValue) {
            if (element instanceof LongTag numeric) {
                return NbtAccess.number(numeric).longValue();
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, Long value) {
            buf.writeVarLong(value == null ? 0L : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public Long fromNetwork(FriendlyByteBuf buf) {
            return buf.readVarLong();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "long";
        }
    }

    /**
     * IEEE 754 single-precision values in NBT and network buffers.
     */
    private enum FloatCodec implements FieldCodec<Float> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, Float value) {
            nbt.putFloat(key, value == null ? 0F : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Float fromNbt(CompoundTag nbt, String key, Float defaultValue) {
            return nbt.get(key) instanceof NumericTag number
                    ? NbtAccess.number(number).floatValue()
                    : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(Float value) {
            return FloatTag.valueOf(value == null ? 0F : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Float fromNbtElement(Tag element, Float defaultValue) {
            if (element instanceof FloatTag numeric) {
                return NbtAccess.number(numeric).floatValue();
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, Float value) {
            buf.writeFloat(value == null ? 0F : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public Float fromNetwork(FriendlyByteBuf buf) {
            return buf.readFloat();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "float";
        }
    }

    /**
     * IEEE 754 double-precision values in NBT and network buffers.
     */
    private enum DoubleCodec implements FieldCodec<Double> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, Double value) {
            nbt.putDouble(key, value == null ? 0D : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Double fromNbt(CompoundTag nbt, String key, Double defaultValue) {
            return nbt.get(key) instanceof NumericTag number
                    ? NbtAccess.number(number).doubleValue()
                    : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(Double value) {
            return DoubleTag.valueOf(value == null ? 0D : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public Double fromNbtElement(Tag element, Double defaultValue) {
            if (element instanceof DoubleTag numeric) {
                return NbtAccess.number(numeric).doubleValue();
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, Double value) {
            buf.writeDouble(value == null ? 0D : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public Double fromNetwork(FriendlyByteBuf buf) {
            return buf.readDouble();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "double";
        }
    }

    /**
     * Text values in NBT string tags and Minecraft's network string encoding.
     */
    private enum StringCodec implements FieldCodec<String> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, String value) {
            nbt.putString(key, value == null ? "" : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public String fromNbt(CompoundTag nbt, String key, String defaultValue) {
            return NbtAccess.string(nbt.get(key), defaultValue);
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(String value) {
            return StringTag.valueOf(value == null ? "" : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public String fromNbtElement(Tag element, String defaultValue) {
            if (element instanceof StringTag text) {
                return NbtAccess.string(text);
            }
            return defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, String value) {
            buf.writeUtf(value == null ? "" : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public String fromNetwork(FriendlyByteBuf buf) {
            return buf.readUtf();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "string";
        }
    }

    /**
     * UUID values using four-int NBT arrays and Minecraft's network UUID encoding.
     */
    private enum UuidCodec implements FieldCodec<UUID> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, UUID value) {
            if (value != null) {
                NbtAccess.putUuid(nbt, key, value);
            } else nbt.remove(key);
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public UUID fromNbt(CompoundTag nbt, String key, UUID defaultValue) {
            return NbtAccess.uuid(nbt, key, defaultValue);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, UUID value) {
            buf.writeUUID(value == null ? new UUID(0L, 0L) : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public UUID fromNetwork(FriendlyByteBuf buf) {
            return buf.readUUID();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "uuid";
        }
    }

    /**
     * Compound NBT values with defensive deep copies for field ownership.
     */
    private enum CompoundCodec implements FieldCodec<CompoundTag> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, CompoundTag value) {
            nbt.put(key, value == null ? new CompoundTag() : value.copy());
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt source or destination NBT compound, non-null
         * @param key stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public CompoundTag fromNbt(CompoundTag nbt, String key, CompoundTag defaultValue) {
            if (nbt.get(key) instanceof CompoundTag child) {
                return child.copy();
            }
            return defaultValue == null ? null : defaultValue.copy();
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        @Override
        public Tag toNbtElement(CompoundTag value) {
            return value == null ? new CompoundTag() : value.copy();
        }

        /**
         * {@inheritDoc}
         *
         * @param element nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public CompoundTag fromNbtElement(Tag element, CompoundTag defaultValue) {
            if (element instanceof CompoundTag child) {
                return child.copy();
            }
            return defaultValue == null ? null : defaultValue.copy();
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, CompoundTag value) {
            buf.writeNbt(value == null ? new CompoundTag() : value);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public CompoundTag fromNetwork(FriendlyByteBuf buf) {
            CompoundTag read = buf.readNbt();
            return read == null ? new CompoundTag() : read;
        }

        /**
         * {@inheritDoc}
         *
         * @param value field value; null handling is defined by the codec
         * @return defensive copy, or the same reference for immutable values; possibly null
         */
        @Override
        public CompoundTag copy(CompoundTag value) {
            return value == null ? null : value.copy();
        }

        /**
         * {@inheritDoc}
         *
         * @return stable codec schema name, never null
         */
        @Override
        public String schemaName() {
            return "compound";
        }
    }
}
