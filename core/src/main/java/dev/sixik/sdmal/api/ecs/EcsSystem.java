package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import java.util.List;

/**
 * Snapshot-based system: prepare/apply run on the world thread; only compute may run on workers.
 * Snapshots/results must be independently owned data, without live component or Level references.
 * Systems are registered explicitly on a world's scheduler; old TickableComponents keep their
 * serial contract.
 *
 * @param <S> prepared snapshot type
 * @param <R> computation result type
 */
public interface EcsSystem<S, R> {
    /**
     * @return stable non-empty system id, unique within one manager
     */
    String id();

    /**
     * @return immutable access declaration used for ordering and component restriction checks
     */
    EcsAccess access();

    /**
     * @return phase of execution, NORMAL by default
     */
    default TickPhase phase() {
        return TickPhase.NORMAL;
    }

    /**
     * @return explicit permission to compute on workers; defaults to false
     */
    default boolean parallel() {
        return false;
    }

    /**
     * @return explicit permission to defer unstarted work when its phase budget is exhausted
     */
    default boolean mayDefer() {
        return false;
    }

    /**
     * @return system ids whose results must be committed before preparing this system
     */
    default List<String> after() {
        return ObjectArrayView.of();
    }

    /**
     * Selects global system scheduling; object sleep/intervals remain under the existing manager
     * contract.
     *
     * @param tick current manager tick sequence
     * @return true when this system has work scheduled for the tick
     */
    default boolean isDue(long tick) {
        return true;
    }

    /**
     * Captures the input in the owner thread, after preceding dependency groups committed.
     *
     * @param context world commands and owner-thread world access
     * @param manager world's object manager
     * @return independently owned input snapshot; may be null when supported by compute
     */
    S prepare(EcsContext context, EcsObjectManager manager);

    /**
     * Computes from the snapshot without touching the live manager, components or Level. World
     * changes are buffered through context.enqueue and are published only on successful apply.
     *
     * @param snapshot exclusively owned input from prepare
     * @param context  deferred command API; live world access is forbidden during compute
     * @return independently owned result, possibly null
     */
    R compute(S snapshot, EcsContext context);

    /**
     * Applies results on the owner thread in stable dependency-group order.
     *
     * @param result  computed result
     * @param context owner-thread world access and deferred commands
     * @param manager world's object manager
     */
    void apply(R result, EcsContext context, EcsObjectManager manager);
}
