package dev.sixik.sdmal.api.network;

/**
 * Exactly-once completion handle supplied to an asynchronous request handler.
 *
 * @param <R> response value type
 */
public interface AsyncResponder<R> {
    /**
     * Completes the request and sends the response to the original connection.
     *
     * @param response response value; null is allowed only for {@code Void} requests
     */
    void complete(R response);

    /**
     * Returns a deliberate remote failure without throwing from the request handler.
     *
     * @param message user-facing failure description, bounded to 1024 characters
     */
    void fail(String message);

    /**
     * Completes from background work; serialization snapshots the result on its completing thread,
     * and the framework schedules the actual reply on the original game's executor.
     *
     * @param result background result stage; must not mutate Minecraft off the game thread
     */
    default void completeWith(java.util.concurrent.CompletionStage<? extends R> result) {
        java.util.Objects.requireNonNull(result, "result").whenComplete((value, failure) -> {
            if (failure == null) {
                complete(value);
                return;
            }
            fail("Asynchronous request failed");
        });
    }

    /**
     * @return whether this response has completed, failed, expired or lost its connection
     */
    boolean isDone();
}
