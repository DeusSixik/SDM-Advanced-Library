package dev.sixik.sdmal.core.ecs;

/**
 * Base for all {@code core.ecs} failures. Unchecked to keep hot paths clean.
 */
public class WorldObjectException extends RuntimeException {

    /**
     * Creates a WorldObjectException with the supplied configuration.
     * @param message diagnostic context of the failure
     */
    public WorldObjectException(String message) {
        super(message);
    }

    /**
     * Creates a WorldObjectException with the supplied configuration.
     * @param message diagnostic context of the failure
     * @param cause original failure to preserve in the exception
     */
    public WorldObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
