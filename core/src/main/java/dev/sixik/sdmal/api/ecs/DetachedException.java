package dev.sixik.sdmal.api.ecs;

/**
 * Thrown when attaching a component whose owner's state forbids it.
 */
public final class DetachedException extends WorldObjectException {

    /**
     * Creates a DetachedException with the supplied configuration.
     *
     * @param message diagnostic context of the failure
     */
    public DetachedException(String message) {
        super(message);
    }
}
