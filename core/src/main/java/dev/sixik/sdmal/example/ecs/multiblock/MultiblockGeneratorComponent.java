package dev.sixik.sdmal.example.ecs.multiblock;

import dev.sixik.sdmal.api.ecs.SnapshotTickableComponent;
import dev.sixik.sdmal.api.ecs.EcsContext;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsNoTickSkip;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.BlockEntityComponent;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.RenderStateComponent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Controller-owned production. World reads stay in capture/apply; compute uses an exclusive
 * primitive frame. Only energy persists. Formation is recomputed after load and never trusted from
 * disk.
 */
@EcsComponent(id = "sdmal_example:multiblock_generator")
@EcsNoTickSkip
public final class MultiblockGeneratorComponent
        extends RenderStateComponent<MultiblockGeneratorComponent.View>
        implements SnapshotTickableComponent<
                MultiblockGeneratorComponent.Frame, MultiblockGeneratorComponent.Frame> {
    /**
     * Maximum stored energy in example units.
     */
    public static final int CAPACITY = 10_000;

    /**
     * Energy produced per active, formed tick; no catch-up occurs while unavailable.
     */
    public static final int ENERGY_PER_TICK = 10;

    /**
     * Stored energy in [0,CAPACITY], retained independently of formation.
     */
    @EcsPersist("energy")
    @EcsSync
    private int energy;

    /**
     * Latest owner-thread formation result, synchronized but intentionally not persisted.
     */
    @EcsSync("status")
    private MultiblockStatus status = MultiblockStatus.CHECKING;

    /**
     * Latest nine-bit match mask, synchronized for progress and first-missing-part diagnostics.
     */
    @EcsSync("matched_parts")
    private int matchedMask;

    /**
     * Exclusive frame reused only after the scheduler's phase barrier.
     */
    private final Frame frame = new Frame();

    /**
     * Owner-thread cursor used by capture and commit validation, never exposed to workers.
     */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /**
     * Creates an empty controller component without accessing a Minecraft world.
     */
    public MultiblockGeneratorComponent() {}

    /**
     * Captures formation and energy on the world thread without retaining a Level or live component
     * in the frame.
     *
     * @param context owner-thread capture context
     * @return exclusively owned primitive input/output frame
     */
    @Override
    public Frame capture(EcsContext context) {
        var binding = requireOwner().getSingle(BlockEntityComponent.class).orElseThrow();
        inspect(context.level(), binding.position(), frame);
        frame.energy = energy;
        return frame;
    }

    /**
     * Calculates production without reading live fields, world objects or neighboring components.
     *
     * @param input independent frame owned by this dispatch
     * @param context restricted worker context
     * @return the same frame with its nextEnergy result populated
     */
    @Override
    public Frame compute(Frame input, EcsContext context) {
        int stored = Math.max(0, Math.min(CAPACITY, input.energy));
        input.nextEnergy =
                input.status == MultiblockStatus.FORMED
                        ? Math.min(CAPACITY, stored + ENERGY_PER_TICK)
                        : stored;
        return input;
    }

    /**
     * Revalidates formed structures before production commit, then publishes dirty/network/render
     * state.
     *
     * @param result completed independent frame
     * @param context owner-thread application context
     */
    @Override
    public void apply(Frame result, EcsContext context) {
        var binding = requireOwner().getSingle(BlockEntityComponent.class).orElseThrow();
        if (result.status == MultiblockStatus.FORMED) {
            inspect(context.level(), binding.position(), result);
        }
        int next =
                result.status == MultiblockStatus.FORMED
                        ? result.nextEnergy
                        : Math.max(0, Math.min(CAPACITY, energy));
        boolean energyChanged = next != energy;
        boolean structureChanged = status != result.status || matchedMask != result.matchedMask;
        if (!energyChanged && !structureChanged) return;
        energy = next;
        status = result.status;
        matchedMask = result.matchedMask;
        publishView();
        if (energyChanged) binding.setChanged();
        if (structureChanged || context.tick() % 20 == 0 || energyChanged && energy == CAPACITY) {
            binding.requestClientUpdate();
        }
    }

    /**
     * Rebuilds the immutable projection after a simulation commit or disk/network restoration.
     */
    public void publishView() {
        publishState(new View(energy, status, matchedMask));
    }

    /**
     * Samples world state after checking all required chunks and bounds.
     *
     * @param level owner-thread world
     * @param origin immutable controller coordinate
     * @param target reusable frame receiving status/mask only; energy fields remain untouched
     */
    private void inspect(Level level, BlockPos origin, Frame target) {
        target.status = MultiblockPattern.availability(level, origin, cursor);
        target.matchedMask = 0;
        if (target.status != MultiblockStatus.CHECKING) return;
        target.matchedMask = MultiblockPattern.matches(level, origin, cursor);
        target.status =
                target.matchedMask == MultiblockPattern.COMPLETE_MASK
                        ? MultiblockStatus.FORMED
                        : MultiblockStatus.INCOMPLETE;
    }

    /**
     * Reusable dispatch value with no live Minecraft/component references. Callers must not retain
     * it across ticks.
     */
    public static final class Frame {
        /**
         * Captured energy in example units.
         */
        int energy;

        /**
         * Computed next energy in example units.
         */
        int nextEnergy;

        /**
         * Captured formation classification.
         */
        MultiblockStatus status = MultiblockStatus.CHECKING;

        /**
         * Captured nine-bit required-part match mask.
         */
        int matchedMask;

        /**
         * Creates a frame for one component or benchmark fixture.
         */
        public Frame() {}
    }

    /**
     * Immutable rendering/diagnostic projection, without Level or mutable component references.
     *
     * @param energy committed stored energy
     * @param status latest formation result
     * @param matchedMask matched required parts in the lowest nine bits
     */
    public record View(int energy, MultiblockStatus status, int matchedMask) {
        /**
         * @return number of correct required parts, excluding the controller
         */
        public int matchedParts() {
            return Integer.bitCount(matchedMask & MultiblockPattern.COMPLETE_MASK);
        }

        /**
         * @return first missing required-part index, or -1 when every required part matches
         */
        public int missingPart() {
            int missing = MultiblockPattern.COMPLETE_MASK & ~matchedMask;
            return missing == 0 ? -1 : Integer.numberOfTrailingZeros(missing);
        }
    }
}
