package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;
import dev.sixik.sdmal.api.ecs.annotations.EcsNoTickSkip;

/**
 * Cached class restrictions; annotation reflection is performed once per concrete component class.
 */
final class EcsExecutionPolicy {
    /**
     * Bit requiring computation on the owner thread.
     */
    static final int WORLD_THREAD = 1;

    /**
     * Bit forbidding budget deferral.
     */
    static final int CURRENT_TICK = 2;

    /**
     * Class-loader-safe cache, including inherited annotation restrictions.
     */
    private static final ClassValue<Integer> FLAGS =
            new ClassValue<>() {
                /**
                 * Resolves inherited execution restrictions once.
                 *
                 * @param type component class
                 * @return bit mask of the two independent restrictions
                 */
                @Override
                protected Integer computeValue(Class<?> type) {
                    return (type.isAnnotationPresent(EcsNoThreadSafe.class) ? WORLD_THREAD : 0)
                            | (type.isAnnotationPresent(EcsNoTickSkip.class) ? CURRENT_TICK : 0);
                }
            };

    /**
     * Prevents construction of this metadata helper.
     */
    private EcsExecutionPolicy() {
    }

    /**
     * Returns cached restrictions without reflecting during ticks.
     *
     * @param type component class, non-null
     * @return execution bit mask
     */
    static int flags(Class<?> type) {
        return FLAGS.get(type);
    }
}
