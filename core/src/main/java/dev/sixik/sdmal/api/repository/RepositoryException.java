package dev.sixik.sdmal.api.repository;

/**
 * Reports a repository operation or stored-data failure.
 */
public class RepositoryException extends RuntimeException {
    /**
     * Creates a repository failure with context.
     *
     * @param message operation or data diagnostic
     */
    public RepositoryException(String message) {
        super(message);
    }

    /**
     * Creates a repository failure retaining its backend cause.
     *
     * @param message operation or data diagnostic
     * @param cause   original backend or codec failure
     */
    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
