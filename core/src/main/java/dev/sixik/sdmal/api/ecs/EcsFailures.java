package dev.sixik.sdmal.api.ecs;

/**
 * Separates recoverable component failures from fatal VM/linkage failures.
 */
final class EcsFailures {
    /**
     * Prevents construction of the failure policy.
     */
    private EcsFailures() {}

    /**
     * Propagates fatal failures instead of aggregating them and continuing simulation.
     *
     * @param failure caught failure, non-null
     * @throws VirtualMachineError if the VM cannot safely continue
     * @throws ThreadDeath if this thread is being terminated
     * @throws LinkageError if class linkage is broken
     */
    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) throw error;
        if (failure instanceof ThreadDeath error) throw error;
        if (failure instanceof LinkageError error) throw error;
    }
}
