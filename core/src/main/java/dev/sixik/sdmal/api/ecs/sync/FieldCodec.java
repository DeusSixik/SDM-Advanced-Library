package dev.sixik.sdmal.api.ecs.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

/**
 * Describes how a single component field is read and written to NBT and the network buffer.
 *
 * <p>This is a port of the {@code sdmshop2} JSON-based {@code FieldCodec}: NBT replaces JSON as the
 * disk format, {@link FriendlyByteBuf} stays the network transport. Implementations must be
 * stateless and thread-safe; a codec is shared by every {@link TrackedField} of its value type.
 *
 * @param <Value> field value type
 */
public interface FieldCodec<Value> {

    /**
     * Writes the field value into {@code nbt} under {@code key}.
     *
     * @param nbt   source or destination NBT compound, non-null
     * @param key   stable NBT field key, non-null
     * @param value field value; null handling is defined by the codec
     */
    void toNbt(CompoundTag nbt, String key, Value value);

    /**
     * Reads the field value from {@code nbt}, or returns {@code defaultValue} when the key is
     * absent.
     *
     * @param nbt          source or destination NBT compound, non-null
     * @param key          stable NBT field key, non-null
     * @param defaultValue fallback for missing data, possibly null
     * @return decoded value, or defaultValue for missing/unsupported data
     */
    Value fromNbt(CompoundTag nbt, String key, Value defaultValue);

    /**
     * Writes the value as a standalone {@link Tag}. Used by nested codecs (lists, optionals) to
     * avoid building a temporary {@link CompoundTag} per element.
     *
     * @param value field value; null handling is defined by the codec
     * @return encoded standalone NBT tag; null only if the configured writer permits it
     */
    default Tag toNbtElement(Value value) {
        CompoundTag nbt = new CompoundTag();
        toNbt(nbt, "value", value);
        Tag element = nbt.get("value");
        if (element == null) {
            throw new SyncException("Codec " + schemaName() + " wrote no 'value' tag");
        }
        return element;
    }

    /**
     * Reads the value from a standalone {@link Tag}. A {@code null} element yields {@code
     * defaultValue}.
     *
     * @param element      nested value codec or NBT element as required by this method
     * @param defaultValue fallback for missing data, possibly null
     * @return decoded value, or defaultValue for missing/unsupported data
     */
    default Value fromNbtElement(Tag element, Value defaultValue) {
        if (element == null) {
            return defaultValue;
        }
        CompoundTag nbt = new CompoundTag();
        nbt.put("value", element);
        return fromNbt(nbt, "value", defaultValue);
    }

    /**
     * Writes the value into the network buffer.
     *
     * @param buf   network buffer; reader/writer indices advance on success
     * @param value field value; null handling is defined by the codec
     */
    void toNetwork(FriendlyByteBuf buf, Value value);

    /**
     * Reads the value from the network buffer.
     *
     * @param buf network buffer; reader/writer indices advance on success
     * @return decoded field value; null handling follows the codec contract
     */
    Value fromNetwork(FriendlyByteBuf buf);

    /**
     * Creates a defensive copy for snapshot/diff logic. Immutable values (ints, strings, records)
     * may return as-is.
     *
     * @param value field value; null handling is defined by the codec
     * @return defensive copy, or the same reference for immutable values; possibly null
     */
    default Value copy(Value value) {
        return value;
    }

    /**
     * Compares two values with codec-specific rules.
     *
     * @param first  first value to compare, possibly null
     * @param second second value to compare, possibly null
     * @return true if codec equality considers both values equal
     */
    default boolean areEqual(Value first, Value second) {
        return Objects.equals(first, second);
    }

    /**
     * Returns the codec name used for network schema compatibility checks.
     *
     * @return stable codec schema name, never null
     */
    default String schemaName() {
        return getClass().getName();
    }

    /**
     * Creates a builder for ad-hoc codec declarations.
     *
     * @param <Value> field or callback value type
     * @return new empty codec builder, never null
     */
    static <Value> Builder<Value> builder() {
        return new Builder<>();
    }

    /**
     * Builder for assembling a codec from read/write functions.
     */
    final class Builder<Value> {
        /**
         * Stable codec fingerprint name; null lets the builder choose a default.
         */
        private String schemaName;

        /**
         * Function encoding a named value into an NBT compound.
         */
        private NbtWriter<Value> nbtWriter;

        /**
         * Function decoding a named value with a fallback for missing data.
         */
        private NbtReader<Value> nbtReader;

        /**
         * Optional direct standalone-tag writer; null selects compound wrapping.
         */
        private NbtElementWriter<Value> nbtElementWriter;

        /**
         * Optional direct standalone-tag reader; null selects compound wrapping.
         */
        private NbtElementReader<Value> nbtElementReader;

        /**
         * Function encoding a field value into a network buffer.
         */
        private NetworkWriter<Value> networkWriter;

        /**
         * Function decoding a field value from a network buffer.
         */
        private NetworkReader<Value> networkReader;

        /**
         * Defensive copy function; immutable values may be returned unchanged.
         */
        private ValueCopier<Value> copier = value -> value;

        /**
         * Value equality predicate used to suppress ineffective updates.
         */
        private ValueEquality<Value> equality = Objects::equals;

        /**
         * Sets the stable codec name for the network schema.
         *
         * @param schemaName stable codec identifier used in schema fingerprints
         * @return this builder for chained declarations
         */
        public Builder<Value> schema(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        /**
         * Sets the NBT read/write functions.
         *
         * @param writer function encoding a value in the selected representation
         * @param reader function decoding a value from the selected representation
         * @return this builder for chained declarations
         */
        public Builder<Value> nbt(NbtWriter<Value> writer, NbtReader<Value> reader) {
            this.nbtWriter = writer;
            this.nbtReader = reader;
            return this;
        }

        /**
         * Sets fast standalone-{@link Tag} read/write functions.
         *
         * @param writer function encoding a value in the selected representation
         * @param reader function decoding a value from the selected representation
         * @return this builder for chained declarations
         */
        public Builder<Value> nbtElement(
                NbtElementWriter<Value> writer, NbtElementReader<Value> reader) {
            this.nbtElementWriter = writer;
            this.nbtElementReader = reader;
            return this;
        }

        /**
         * Sets the network buffer read/write functions.
         *
         * @param writer function encoding a value in the selected representation
         * @param reader function decoding a value from the selected representation
         * @return this builder for chained declarations
         */
        public Builder<Value> network(NetworkWriter<Value> writer, NetworkReader<Value> reader) {
            this.networkWriter = writer;
            this.networkReader = reader;
            return this;
        }

        /**
         * Sets how values are copied for snapshots.
         *
         * @param copier defensive copying function; null selects identity copying
         * @return this builder for chained declarations
         */
        public Builder<Value> copy(ValueCopier<Value> copier) {
            this.copier = copier == null ? value -> value : copier;
            return this;
        }

        /**
         * Sets how values are compared.
         *
         * @param equality value comparison function; null selects Objects.equals
         * @return this builder for chained declarations
         */
        public Builder<Value> equality(ValueEquality<Value> equality) {
            this.equality = equality == null ? Objects::equals : equality;
            return this;
        }

        /**
         * Builds the codec, verifying that all mandatory functions are set.
         *
         * @return codec defining the declared value representation, never null
         */
        public FieldCodec<Value> build() {
            if (nbtWriter == null) {
                throw new IllegalStateException("Nbt writer is not configured");
            }
            if (nbtReader == null) {
                throw new IllegalStateException("Nbt reader is not configured");
            }
            if (networkWriter == null) {
                throw new IllegalStateException("Network writer is not configured");
            }
            if (networkReader == null) {
                throw new IllegalStateException("Network reader is not configured");
            }
            String finalSchemaName =
                    schemaName == null ? "custom:" + getClass().getName() : schemaName;
            // Freeze functions here: reusing the builder must not change an existing codec.
            NbtWriter<Value> nbtWriter = this.nbtWriter;
            NbtReader<Value> nbtReader = this.nbtReader;
            NbtElementWriter<Value> nbtElementWriter = this.nbtElementWriter;
            NbtElementReader<Value> nbtElementReader = this.nbtElementReader;
            NetworkWriter<Value> networkWriter = this.networkWriter;
            NetworkReader<Value> networkReader = this.networkReader;
            ValueCopier<Value> copier = this.copier;
            ValueEquality<Value> equality = this.equality;

            return new FieldCodec<>() {
                /**
                 * {@inheritDoc}
                 *
                 * @param nbt source or destination NBT compound, non-null
                 * @param key stable NBT field key, non-null
                 * @param value field value; null handling is defined by the codec
                 */
                @Override
                public void toNbt(CompoundTag nbt, String key, Value value) {
                    nbtWriter.write(nbt, key, value);
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
                public Value fromNbt(CompoundTag nbt, String key, Value defaultValue) {
                    return nbtReader.read(nbt, key, defaultValue);
                }

                /**
                 * {@inheritDoc}
                 *
                 * @param value field value; null handling is defined by the codec
                 * @return encoded standalone NBT tag; null only if the configured writer permits it
                 */
                @Override
                public Tag toNbtElement(Value value) {
                    if (nbtElementWriter != null) {
                        Tag element = nbtElementWriter.write(value);
                        if (element == null) {
                            throw new SyncException(
                                    "Codec " + finalSchemaName + " produced null element");
                        }
                        return element;
                    }

                    CompoundTag nbt = new CompoundTag();
                    nbtWriter.write(nbt, "value", value);
                    Tag element = nbt.get("value");
                    if (element == null) {
                        throw new SyncException(
                                "Codec " + finalSchemaName + " wrote no 'value' tag");
                    }
                    return element;
                }

                /**
                 * {@inheritDoc}
                 *
                 * @param element nested value codec or NBT element as required by this method
                 * @param defaultValue fallback for missing data, possibly null
                 * @return decoded value, or defaultValue for missing/unsupported data
                 */
                @Override
                public Value fromNbtElement(Tag element, Value defaultValue) {
                    if (element == null) {
                        return defaultValue;
                    }
                    if (nbtElementReader != null) {
                        return nbtElementReader.read(element, defaultValue);
                    }

                    CompoundTag nbt = new CompoundTag();
                    nbt.put("value", element);
                    return nbtReader.read(nbt, "value", defaultValue);
                }

                /**
                 * {@inheritDoc}
                 *
                 * @param buf network buffer; reader/writer indices advance on success
                 * @param value field value; null handling is defined by the codec
                 */
                @Override
                public void toNetwork(FriendlyByteBuf buf, Value value) {
                    networkWriter.write(buf, value);
                }

                /**
                 * {@inheritDoc}
                 *
                 * @param buf network buffer; reader/writer indices advance on success
                 * @return decoded field value; null handling follows the codec contract
                 */
                @Override
                public Value fromNetwork(FriendlyByteBuf buf) {
                    return networkReader.read(buf);
                }

                /**
                 * {@inheritDoc}
                 *
                 * @param value field value; null handling is defined by the codec
                 * @return defensive copy, or the same reference for immutable values; possibly null
                 */
                @Override
                public Value copy(Value value) {
                    return copier.copy(value);
                }

                /**
                 * {@inheritDoc}
                 *
                 * @param first first value to compare, possibly null
                 * @param second second value to compare, possibly null
                 * @return true if codec equality considers both values equal
                 */
                @Override
                public boolean areEqual(Value first, Value second) {
                    return equality.areEqual(first, second);
                }

                /**
                 * {@inheritDoc}
                 *
                 * @return stable codec schema name, never null
                 */
                @Override
                public String schemaName() {
                    return finalSchemaName;
                }
            };
        }
    }

    /**
     * Writes a value into NBT.
     */
    @FunctionalInterface
    interface NbtWriter<Value> {
        /**
         * Encodes one value using the declared representation.
         *
         * @param nbt   source or destination NBT compound, non-null
         * @param key   stable NBT field key, non-null
         * @param value field value; null handling is defined by the codec
         */
        void write(CompoundTag nbt, String key, Value value);
    }

    /**
     * Reads a value from NBT.
     */
    @FunctionalInterface
    interface NbtReader<Value> {
        /**
         * Decodes one value using the declared representation.
         *
         * @param nbt          source or destination NBT compound, non-null
         * @param key          stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded field value; null handling follows the codec contract
         */
        Value read(CompoundTag nbt, String key, Value defaultValue);
    }

    /**
     * Writes a value as a standalone {@link Tag}.
     */
    @FunctionalInterface
    interface NbtElementWriter<Value> {
        /**
         * Encodes one value using the declared representation.
         *
         * @param value field value; null handling is defined by the codec
         * @return encoded standalone NBT tag; null only if the configured writer permits it
         */
        Tag write(Value value);
    }

    /**
     * Reads a value from a standalone {@link Tag}.
     */
    @FunctionalInterface
    interface NbtElementReader<Value> {
        /**
         * Decodes one value using the declared representation.
         *
         * @param element      nested value codec or NBT element as required by this method
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded field value; null handling follows the codec contract
         */
        Value read(Tag element, Value defaultValue);
    }

    /**
     * Writes a value into the network buffer.
     */
    @FunctionalInterface
    interface NetworkWriter<Value> {
        /**
         * Encodes one value using the declared representation.
         *
         * @param buf   network buffer; reader/writer indices advance on success
         * @param value field value; null handling is defined by the codec
         */
        void write(FriendlyByteBuf buf, Value value);
    }

    /**
     * Reads a value from the network buffer.
     */
    @FunctionalInterface
    interface NetworkReader<Value> {
        /**
         * Decodes one value using the declared representation.
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        Value read(FriendlyByteBuf buf);
    }

    /**
     * Copies a value.
     */
    @FunctionalInterface
    interface ValueCopier<Value> {
        /**
         * Copies a value according to the codec's mutability contract.
         *
         * @param value field value; null handling is defined by the codec
         * @return defensive copy, or the same reference for immutable values; possibly null
         */
        Value copy(Value value);
    }

    /**
     * Compares two values.
     */
    @FunctionalInterface
    interface ValueEquality<Value> {
        /**
         * Compares values using codec-specific equality.
         *
         * @param first  first value to compare, possibly null
         * @param second second value to compare, possibly null
         * @return true if codec equality considers both values equal
         */
        boolean areEqual(Value first, Value second);
    }
}
