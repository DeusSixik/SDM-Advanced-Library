package dev.sixik.sdmal.core.ecs.codecs;

import dev.sixik.sdmal.core.ecs.sync.FieldCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * Minecraft identifier and coordinate codecs shared by every supported game version.
 */
public final class ExternFieldCodecs {

    /**
     * Prevents construction of this stateless codec catalog.
     */
    private ExternFieldCodecs() {
    }

    /**
     * Section origin at X=Y=Z=0, used as a reusable default position.
     */
    public static final SectionPos SECTION_POS_ZERO = SectionPos.of(0, 0, 0);

    /**
     * Returns the shared Minecraft identifier codec with invalid-NBT fallback.
     *
     * @return shared stateless codec instance, never null
     */
    public static ResourceLocationCodec resourceLocation() {
        return ResourceLocationCodec.INSTANCE;
    }

    /**
     * Returns the shared packed block-position codec.
     *
     * @return shared stateless codec instance, never null
     */
    public static BlockPosCodec blockPos() {
        return BlockPosCodec.INSTANCE;
    }

    /**
     * Returns the shared packed chunk-position codec.
     *
     * @return shared stateless codec instance, never null
     */
    public static ChunkPosCodec chunkPos() {
        return ChunkPosCodec.INSTANCE;
    }

    /**
     * Returns the shared packed section-position codec.
     *
     * @return shared stateless codec instance, never null
     */
    public static SectionPosCodec sectionPos() {
        return SectionPosCodec.INSTANCE;
    }

    /**
     * Encodes identifiers as strings in NBT and through Minecraft's network format.
     */
    public enum ResourceLocationCodec implements FieldCodec<ResourceLocation> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt              source or destination NBT compound, non-null
         * @param key              stable NBT field key, non-null
         * @param resourceLocation Minecraft identifier to encode, non-null
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, ResourceLocation resourceLocation) {
            nbt.putString(key, resourceLocation.toString());
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt          source or destination NBT compound, non-null
         * @param key          stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public ResourceLocation fromNbt(CompoundTag nbt, String key, ResourceLocation defaultValue) {
            String text = NbtAccess.string(nbt.get(key), null);
            ResourceLocation decoded = text == null ? null : ResourceLocation.tryParse(text);
            return decoded == null ? defaultValue : decoded;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf              network buffer; reader/writer indices advance on success
         * @param resourceLocation Minecraft identifier to encode, non-null
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, ResourceLocation resourceLocation) {
            buf.writeResourceLocation(resourceLocation);
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public ResourceLocation fromNetwork(FriendlyByteBuf buf) {
            return buf.readResourceLocation();
        }
    }

    /**
     * Encodes block positions using Minecraft's packed 64-bit coordinate representation.
     */
    public enum BlockPosCodec implements FieldCodec<BlockPos> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt      source or destination NBT compound, non-null
         * @param key      stable NBT field key, non-null
         * @param blockPos Minecraft coordinates to encode, non-null
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, BlockPos blockPos) {
            nbt.putLong(key, blockPos.asLong());
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt          source or destination NBT compound, non-null
         * @param key          stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public BlockPos fromNbt(CompoundTag nbt, String key, BlockPos defaultValue) {
            return nbt.get(key) instanceof net.minecraft.nbt.NumericTag number
                    ? BlockPos.of(NbtAccess.number(number).longValue()) : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf      network buffer; reader/writer indices advance on success
         * @param blockPos Minecraft coordinates to encode, non-null
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, BlockPos blockPos) {
            buf.writeLong(blockPos.asLong());
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public BlockPos fromNetwork(FriendlyByteBuf buf) {
            return BlockPos.of(buf.readLong());
        }
    }

    /**
     * Encodes chunk positions as packed X/Z coordinates in a 64-bit value.
     */
    public enum ChunkPosCodec implements FieldCodec<ChunkPos> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt      source or destination NBT compound, non-null
         * @param key      stable NBT field key, non-null
         * @param blockPos Minecraft coordinates to encode, non-null
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, ChunkPos blockPos) {
            nbt.putLong(key, packChunk(blockPos));
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt          source or destination NBT compound, non-null
         * @param key          stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public ChunkPos fromNbt(CompoundTag nbt, String key, ChunkPos defaultValue) {
            return nbt.get(key) instanceof net.minecraft.nbt.NumericTag number
                    ? unpackChunk(NbtAccess.number(number).longValue()) : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf      network buffer; reader/writer indices advance on success
         * @param blockPos Minecraft coordinates to encode, non-null
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, ChunkPos blockPos) {
            buf.writeLong(packChunk(blockPos));
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public ChunkPos fromNetwork(FriendlyByteBuf buf) {
            return unpackChunk(buf.readLong());
        }
    }

    /**
     * Encodes section positions using Minecraft's packed 64-bit coordinate representation.
     */
    public enum SectionPosCodec implements FieldCodec<SectionPos> {
        /**
         * Shared stateless instance used by the public codec or registry accessor.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @param nbt      source or destination NBT compound, non-null
         * @param key      stable NBT field key, non-null
         * @param blockPos Minecraft coordinates to encode, non-null
         */
        @Override
        public void toNbt(CompoundTag nbt, String key, SectionPos blockPos) {
            nbt.putLong(key, blockPos.asLong());
        }

        /**
         * {@inheritDoc}
         *
         * @param nbt          source or destination NBT compound, non-null
         * @param key          stable NBT field key, non-null
         * @param defaultValue fallback for missing data, possibly null
         * @return decoded value, or defaultValue for missing/unsupported data
         */
        @Override
        public SectionPos fromNbt(CompoundTag nbt, String key, SectionPos defaultValue) {
            return nbt.get(key) instanceof net.minecraft.nbt.NumericTag number
                    ? SectionPos.of(NbtAccess.number(number).longValue()) : defaultValue;
        }

        /**
         * {@inheritDoc}
         *
         * @param buf      network buffer; reader/writer indices advance on success
         * @param blockPos Minecraft coordinates to encode, non-null
         */
        @Override
        public void toNetwork(FriendlyByteBuf buf, SectionPos blockPos) {
            buf.writeLong(blockPos.asLong());
        }

        /**
         * {@inheritDoc}
         *
         * @param buf network buffer; reader/writer indices advance on success
         * @return decoded field value; null handling follows the codec contract
         */
        @Override
        public SectionPos fromNetwork(FriendlyByteBuf buf) {
            return SectionPos.of(buf.readLong());
        }
    }

    /**
     * Packs a chunk position using the version's standard Minecraft representation.
     *
     * @param position chunk coordinates, non-null
     * @return packed X/Z coordinates in a long
     */
    private static long packChunk(ChunkPos position) {
        //? if >=26.1 {
        /*return position.pack();
         *///?} else {
        return position.toLong();
        //?}
    }

    /**
     * Unpacks the standard Minecraft chunk representation.
     *
     * @param packed packed X/Z chunk coordinates
     * @return decoded chunk position, never null
     */
    private static ChunkPos unpackChunk(long packed) {
        //? if >=26.1 {
        /*return ChunkPos.unpack(packed);
         *///?} else {
        return new ChunkPos(packed);
        //?}
    }
}
