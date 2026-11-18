package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import java.util.List;
import java.util.Objects;

/**
 * Component-local snapshot ticking, automatically scheduled after attachment to a manager.
 * Capture/apply run on the world thread. Compute may run on workers and must use only its snapshot,
 * immutable constants and context.enqueue; it must not read or change live fields of this
 * component. Same-class instances are computed in independent batches, then committed in stable
 * attachment order. Do not also implement TickableComponent: the two callback contracts are
 * mutually exclusive.
 *
 * @param <S> independently owned snapshot type, without live component/world references
 * @param <R> independently owned result type
 */
public interface SnapshotTickableComponent<S, R> {
    /**
     * Captures input before worker jobs begin, respecting object sleep and tick intervals.
     *
     * @param context owner-thread context; level() is available for bound worlds
     * @return detached snapshot; null is allowed when compute supports it
     */
    S capture(EcsContext context);

    /**
     * Computes exclusively from copied data. This method may also run on the world thread as
     * executor fallback, but live world access and structural manager mutation remain forbidden
     * during computation.
     *
     * @param snapshot exclusively owned input from capture
     * @param context  deferred world commands and current tick sequence
     * @return detached result, possibly null
     */
    R compute(S snapshot, EcsContext context);

    /**
     * Validates and applies the result on the world thread if this exact attachment is still live
     * and awake. Unpublished commands from a failed/stale component are discarded; partial field
     * mutations are not rolled back.
     *
     * @param result  completed result
     * @param context owner-thread context and deferred world commands
     */
    void apply(R result, EcsContext context);

    /**
     * @return execution phase; metadata must be constant for every instance of the concrete class
     */
    default TickPhase tickPhase() {
        return TickPhase.NORMAL;
    }

    /**
     * Declares resources for conflict ordering with other component types and explicit systems.
     * Instances of this concrete class are independent jobs: cross-instance algorithms belong in an
     * EcsSystem. Metadata is captured once per concrete class in each manager, so do not derive it
     * from mutable fields.
     *
     * @return immutable declaration, writing this concrete component class by default
     */
    default EcsAccess tickAccess() {
        return EcsAccess.NONE.write(getClass().asSubclass(Component.class));
    }

    /**
     * @return explicit permission to defer due work for budget reasons; false by default
     */
    default boolean mayDeferTick() {
        return false;
    }

    /**
     * @return predecessor system ids; constant across all instances of this concrete component
     * class
     */
    default List<String> tickAfter() {
        return ObjectArrayView.of();
    }

    /**
     * Returns the automatic group id for optional explicit dependencies. No generated Java classes
     * are involved.
     *
     * @param componentClass concrete snapshot component class
     * @return stable scheduler id derived from the binary Java class name
     */
    static String systemId(Class<?> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        if (!SnapshotTickableComponent.class.isAssignableFrom(componentClass))
            throw new IllegalArgumentException(
                    "Not a snapshot-tickable component: " + componentClass.getName());
        return "sdmal:component/" + componentClass.getName();
    }
}
