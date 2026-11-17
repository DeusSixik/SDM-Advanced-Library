package dev.sixik.sdmal.example.ecs.multiblock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Measures the example's real nine-position matcher and primitive compute, excluding Minecraft
 * chunk lookup. The fixed reader and frames are allocated once per trial; no Mockito or worker
 * scheduling is measured.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MultiblockBenchmark {
    /**
     * Fully formed structure or one missing iron support.
     */
    @Param({"formed", "missing"})
    public String shape;

    /**
     * Controlled fixture reader with a fixed nine-position structure.
     */
    private PatternReader reader;

    /**
     * Immutable origin reused for every matcher invocation.
     */
    private BlockPos origin;

    /**
     * Scratch cursor reused for every block read.
     */
    private BlockPos.MutableBlockPos cursor;

    /**
     * Real production component whose compute must use only its input frame.
     */
    private MultiblockGeneratorComponent component;

    /**
     * Exclusive frame reused after every invocation.
     */
    private MultiblockGeneratorComponent.Frame frame;

    /**
     * Builds all fixture state and initializes actual Minecraft block constants before measurement.
     */
    @Setup
    public void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        reader = new PatternReader(shape.equals("missing"));
        origin = new BlockPos(0, 64, 0);
        cursor = new BlockPos.MutableBlockPos();
        component = new MultiblockGeneratorComponent();
        frame = new MultiblockGeneratorComponent.Frame();
        frame.energy = 9000;
        frame.status =
                shape.equals("formed") ? MultiblockStatus.FORMED : MultiblockStatus.INCOMPLETE;
    }

    /**
     * @return real nine-position match mask, observed to prevent dead-code elimination
     */
    @Benchmark
    public int matchStructure() {
        return MultiblockPattern.matches(reader, origin, cursor);
    }

    /**
     * @return computed energy, without creating snapshots/results inside the benchmark
     */
    @Benchmark
    public int computeProduction() {
        return component.compute(frame, null).nextEnergy;
    }

    /**
     * Lightweight controlled block reader; it intentionally excludes real Level/chunk overhead.
     */
    private static final class PatternReader implements BlockGetter {
        /**
         * Whether the north-west iron support is missing.
         */
        private final boolean missing;

        /**
         * @param missing whether to omit one required support
         */
        private PatternReader(boolean missing) {
            this.missing = missing;
        }

        /**
         * @param pos queried coordinate @return iron, redstone core or one deliberately missing
         *     support
         */
        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (pos.getY() == 65) return Blocks.REDSTONE_BLOCK.defaultBlockState();
            if (missing && pos.getX() == -1 && pos.getZ() == -1)
                return Blocks.AIR.defaultBlockState();
            return Blocks.IRON_BLOCK.defaultBlockState();
        }

        /**
         * @param pos queried coordinate @return null; fixture supports have no block entities
         */
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        /**
         * @param pos queried coordinate @return empty fluid
         */
        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        /**
         * @return fixture world height in blocks
         */
        @Override
        public int getHeight() {
            return 384;
        }

        /**
         * @return legacy minimum fixture Y in blocks
         */
        public int getMinBuildHeight() {
            return -64;
        }

        /**
         * @return modern minimum fixture Y in blocks
         */
        public int getMinY() {
            return -64;
        }
    }
}
