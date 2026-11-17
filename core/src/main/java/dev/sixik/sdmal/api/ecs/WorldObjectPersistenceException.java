package dev.sixik.sdmal.api.ecs;

/**
 * Reports a malformed archive or a component state that cannot be persisted safely.
 */
public class WorldObjectPersistenceException extends WorldObjectException {
    /**
     * Describes an invalid archive or component.
     *
     * @param message diagnostic context
     */
    public WorldObjectPersistenceException(String message) {
        super(message);
    }

    /**
     * Describes a failed component serializer while preserving its original failure.
     *
     * @param message diagnostic context
     * @param cause original non-fatal failure
     */
    public WorldObjectPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * A newer archive must remain untouched; recovering an older backup would lose newer data.
     */
    public static final class FutureFormat extends WorldObjectPersistenceException {
        /**
         * Rejects a format newer than this implementation understands.
         *
         * @param version unsupported positive archive version
         */
        public FutureFormat(int version) {
            super("Unsupported future ECS archive format " + version);
        }
    }
}
