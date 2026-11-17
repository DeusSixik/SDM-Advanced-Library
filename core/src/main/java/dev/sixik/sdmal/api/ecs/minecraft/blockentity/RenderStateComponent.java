package dev.sixik.sdmal.api.ecs.minecraft.blockentity;

import dev.sixik.sdmal.api.ecs.AnnotatedWorldObjectComponent;

/**
 * Publishes an immutable rendering projection independently of mutable simulation fields. A
 * renderer reads state() only. The world thread publishes after applying a result or receiving a
 * packet.
 *
 * @param <S> deeply immutable render-state value; must not contain Level, BlockEntity or live
 *     components
 */
public abstract class RenderStateComponent<S> extends AnnotatedWorldObjectComponent {
    /**
     * Last fully published immutable state; null before the first publication.
     */
    private volatile S state;

    /**
     * Creates an unpublished rendering projection.
     */
    protected RenderStateComponent() {}

    /**
     * Replaces the whole render projection on the owning world thread.
     *
     * @param next deeply immutable state, possibly null to hide the rendering projection
     */
    protected final void publishState(S next) {
        owner().flatMap(object -> object.manager()).ifPresent(manager -> manager.checkThread());
        state = next;
    }

    /**
     * @return last complete immutable render state, possibly null; no simulation data is exposed
     */
    public final S state() {
        return state;
    }
}
