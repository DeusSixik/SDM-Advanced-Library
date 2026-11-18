package dev.sixik.sdmal.api.network;

/**
 * A well-formed remote request was rejected or its handler could not produce a result.
 */
public final class RemoteRequestException extends RuntimeException {
    /**
     * @param message bounded remote failure description
     */
    public RemoteRequestException(String message) {
        super(message);
    }
}
