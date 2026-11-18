package dev.sixik.sdmal.api.ecs;

/**
 * Base for all {@code api.ecs} failures. Unchecked to keep hot paths clean.
 */
public class EcsObjectException extends RuntimeException {

    /**
     * Creates a EcsObjectException with the supplied configuration.
     *
     * @param message diagnostic context of the failure
     */
    public EcsObjectException(String message) {
        super(message);
    }

    /**
     * Creates a EcsObjectException with the supplied configuration.
     *
     * @param message diagnostic context of the failure
     * @param cause   original failure to preserve in the exception
     */
    public EcsObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
