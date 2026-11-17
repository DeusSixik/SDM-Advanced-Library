package dev.sixik.sdmal.example.ecs.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Fixed example pattern: eight iron blocks around the controller at its Y and a redstone block
 * above it. The arrays are immutable after initialization; every scan uses its caller's reusable
 * position cursor.
 */
final class MultiblockPattern {
    /**
     * Required positions excluding the controller, in stable diagnostic/mask order.
     */
    static final int PARTS = 9;

    /**
     * Bit mask with every required position set; only the lowest nine bits are used.
     */
    static final int COMPLETE_MASK = (1 << PARTS) - 1;

    /**
     * X offsets in blocks from the controller. Last entry is the redstone core.
     */
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1, 0};

    /**
     * Z offsets in blocks from the controller. Last entry is the redstone core.
     */
    private static final int[] DZ = {-1, -1, -1, 0, 0, 1, 1, 1, 0};

    /**
     * Prevents construction of the fixed pattern utility.
     */
    private MultiblockPattern() {}

    /**
     * Preflights every required position before reading block states; no absent chunk is loaded.
     *
     * @param level server world, accessed only on its owner thread
     * @param origin controller coordinate
     * @param cursor caller-owned scratch position, not retained
     * @return CHECKING when reads are allowed, otherwise the reason inspection must stop
     */
    static MultiblockStatus availability(
            Level level, BlockPos origin, BlockPos.MutableBlockPos cursor) {
        for (int i = 0; i < PARTS; i++) {
            position(origin, i, cursor);
            if (level.isOutsideBuildHeight(cursor)
                    || !level.getWorldBorder().isWithinBounds(cursor)) {
                return MultiblockStatus.OUT_OF_BOUNDS;
            }
            if (!level.hasChunkAt(cursor)) return MultiblockStatus.UNLOADED;
        }
        return MultiblockStatus.CHECKING;
    }

    /**
     * Reads only already available positions and builds the nine-bit match mask.
     *
     * @param blocks loaded block reader, never used from worker computation
     * @param origin controller coordinate
     * @param cursor caller-owned scratch position, not retained
     * @return match mask, with a bit set for each correct required block
     */
    static int matches(BlockGetter blocks, BlockPos origin, BlockPos.MutableBlockPos cursor) {
        int mask = 0;
        for (int i = 0; i < PARTS; i++) {
            position(origin, i, cursor);
            var expected = i == PARTS - 1 ? Blocks.REDSTONE_BLOCK : Blocks.IRON_BLOCK;
            if (blocks.getBlockState(cursor).is(expected)) mask |= 1 << i;
        }
        return mask;
    }

    /**
     * Maps a required-part index to its world position without allocating a BlockPos.
     *
     * @param origin controller coordinate
     * @param index required-part ordinal in [0,9)
     * @param target writable output cursor
     * @throws IndexOutOfBoundsException if index is not a required-part ordinal
     */
    static void position(BlockPos origin, int index, BlockPos.MutableBlockPos target) {
        target.set(
                origin.getX() + DX[index],
                origin.getY() + (index == PARTS - 1 ? 1 : 0),
                origin.getZ() + DZ[index]);
    }
}
