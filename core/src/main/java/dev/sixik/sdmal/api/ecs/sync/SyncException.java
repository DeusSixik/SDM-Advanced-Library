package dev.sixik.sdmal.api.ecs.sync;

/**
 * Base for all {@code api.ecs.sync} failures. Unchecked to keep tick paths clean.
 */
public class SyncException extends RuntimeException {

    /**
     * Creates a SyncException with the supplied configuration.
     *
     * @param message diagnostic context of the failure
     */
    public SyncException(String message) {
        super(message);
    }

    /**
     * Creates a SyncException with the supplied configuration.
     *
     * @param message diagnostic context of the failure
     * @param cause original failure to preserve in the exception
     */
    public SyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
