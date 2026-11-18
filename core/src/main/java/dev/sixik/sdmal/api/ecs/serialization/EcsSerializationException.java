package dev.sixik.sdmal.api.ecs.serialization;

import dev.sixik.sdmal.api.ecs.EcsPersistenceException;

/**
 * Reports an invalid annotated schema or value with its component/member path.
 */
public final class EcsSerializationException extends EcsPersistenceException {
    /**
     * Describes an invalid schema or value.
     *
     * @param message diagnostic path and reason
     */
    public EcsSerializationException(String message) {
        super(message);
    }

    /**
     * Preserves a non-fatal adapter or access failure.
     *
     * @param message diagnostic path and reason
     * @param cause   original failure
     */
    public EcsSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
