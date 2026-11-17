package dev.sixik.sdmal.api.ecs;

/**
 * Thrown by {@code attach} when the type's {@link WorldObjectComponentType.DuplicatePolicy#REJECT}
 * forbids a second instance.
 */
public final class DuplicateComponentException extends WorldObjectException {

    /**
     * Creates a DuplicateComponentException with the supplied configuration.
     *
     * @param message diagnostic context of the failure
     */
    public DuplicateComponentException(String message) {
        super(message);
    }
}
