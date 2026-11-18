package dev.sixik.sdmal.api.ecs;

/**
 * Component that receives ticks from {@link EcsObject#tick(long)}.
 *
 * <p>The {@code tick} argument is a manager-wide monotonic counter, not a timestamp. A throwing
 * {@code onTick} does not stop other components: the failure is reported to {@link
 * EcsObjectListener#onComponentError} and aggregated into {@link EcsObject.TickException}.
 */
public interface TickableComponent {

    /**
     * Performs one tick. Must be thread-safe, see {@link Component}.
     *
     * @param tick manager-wide tick sequence, not wall-clock time
     */
    void onTick(long tick);
}
