package dev.sixik.sdmal.example.ecs.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Server-thread demo builder. Preflights every required position before placing missing parts in
 * air. Neighbor callbacks can interrupt placement; a later click can complete the remaining empty
 * positions.
 */
final class MultiblockAssembler {
    /**
     * Prevents utility construction.
     */
    private MultiblockAssembler() {}

    /**
     * Attempts to assemble a live controller's nine required parts without loading chunks or
     * replacing obstacles.
     *
     * @param level server world, accessed on its owner thread
     * @param controller exact installed controller shell
     * @param player player whose build permissions apply to every required position
     * @return localized outcome; a preflight failure never places any block
     */
    static Result assemble(Level level, MultiblockControllerBlockEntity controller, Player player) {
        if (level.isClientSide() || player.isSpectator() || !player.mayBuild())
            return Result.DENIED;
        BlockPos origin = controller.getBlockPos();
        if (!level.hasChunkAt(origin)) return Result.UNLOADED;
        if (controller.isRemoved() || level.getBlockEntity(origin) != controller)
            return Result.INTERRUPTED;
        if (!level.mayInteract(player, origin)) return Result.DENIED;
        var cursor = new BlockPos.MutableBlockPos();
        var availability = MultiblockPattern.availability(level, origin, cursor);
        if (availability == MultiblockStatus.UNLOADED) return Result.UNLOADED;
        if (availability == MultiblockStatus.OUT_OF_BOUNDS) return Result.OUT_OF_BOUNDS;
        boolean missing = false;
        for (int i = 0; i < MultiblockPattern.PARTS; i++) {
            MultiblockPattern.position(origin, i, cursor);
            if (!level.mayInteract(player, cursor)) return Result.DENIED;
            var expected =
                    i == MultiblockPattern.PARTS - 1 ? Blocks.REDSTONE_BLOCK : Blocks.IRON_BLOCK;
            var current = level.getBlockState(cursor);
            if (current.is(expected)) continue;
            if (!current.isAir()) return Result.BLOCKED;
            missing = true;
        }
        if (!missing) return Result.ALREADY_COMPLETE;
        for (int i = 0; i < MultiblockPattern.PARTS; i++) {
            if (controller.isRemoved()
                    || !level.hasChunkAt(origin)
                    || level.getBlockEntity(origin) != controller) return Result.INTERRUPTED;
            MultiblockPattern.position(origin, i, cursor);
            if (level.isOutsideBuildHeight(cursor)
                    || !level.hasChunkAt(cursor)
                    || !level.getWorldBorder().isWithinBounds(cursor)
                    || !level.mayInteract(player, cursor)) {
                return Result.INTERRUPTED;
            }
            var expected =
                    i == MultiblockPattern.PARTS - 1 ? Blocks.REDSTONE_BLOCK : Blocks.IRON_BLOCK;
            var current = level.getBlockState(cursor);
            if (current.is(expected)) continue;
            if (!current.isAir() || !level.setBlock(cursor, expected.defaultBlockState(), 3)) {
                return Result.INTERRUPTED;
            }
        }
        if (controller.isRemoved()
                || !level.hasChunkAt(origin)
                || level.getBlockEntity(origin) != controller) return Result.INTERRUPTED;
        if (MultiblockPattern.availability(level, origin, cursor) != MultiblockStatus.CHECKING) {
            return Result.INTERRUPTED;
        }
        return MultiblockPattern.matches(level, origin, cursor) == MultiblockPattern.COMPLETE_MASK
                ? Result.BUILT
                : Result.INTERRUPTED;
    }

    /**
     * Stable localized feedback for the controller's assembly action.
     */
    enum Result {
        /**
         * All missing parts were placed and the final structure matches.
         */
        BUILT("built"),
        /**
         * Every required block was already correct; no placement was necessary.
         */
        ALREADY_COMPLETE("already_complete"),
        /**
         * A required chunk is unloaded; preflight performed no placement.
         */
        UNLOADED("unloaded"),
        /**
         * A required part lies outside build height or the border.
         */
        OUT_OF_BOUNDS("out_of_bounds"),
        /**
         * An unexpected non-air block occupies a required position.
         */
        BLOCKED("blocked"),
        /**
         * The player's game mode or world interaction permissions forbid building here.
         */
        DENIED("denied"),
        /**
         * Placement or neighbor callbacks interrupted assembly; completed parts remain.
         */
        INTERRUPTED("interrupted");

        /**
         * Translation key for this assembly outcome.
         */
        private final String messageKey;

        /**
         * Creates an outcome with its stable translation suffix.
         *
         * @param suffix suffix under the multiblock assembly message namespace
         */
        Result(String suffix) {
            messageKey = "message.sdmal.ecs_multiblock.assembly." + suffix;
        }

        /**
         * @return complete translation key for player feedback
         */
        String messageKey() {
            return messageKey;
        }
    }
}
