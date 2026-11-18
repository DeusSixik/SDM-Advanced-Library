package dev.sixik.sdmal.api.repository;

/**
 * Signals that a conditional write found a different revision or a missing record.
 * Reload the record and reconsider the change; blindly retrying can overwrite another edit.
 */
public final class RepositoryConflictException extends RepositoryException {
    /**
     * Describes the failed conditional write.
     *
     * @param key encoded key of the missing or concurrently changed record
     */
    public RepositoryConflictException(String key) {
        super("Repository revision conflict for key " + key + "; reload before retrying");
    }
}
