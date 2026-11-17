package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.core.ecs.WorldObjectPersistenceException;

import java.lang.reflect.InvocationTargetException;

/**
 * Reports an invalid annotated schema or value with its component/member path.
 */
public final class EcsSerializationException extends WorldObjectPersistenceException {
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

    /**
     * Adds a member path without concealing fatal JVM failures from constructors or adapters.
     *
     * @param path    schema or value location
     * @param failure original invocation/access failure
     * @return contextual non-fatal exception
     */
    static EcsSerializationException at(String path, Throwable failure) {
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null)
            failure = invocation.getCause();
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (failure instanceof LinkageError fatal) throw fatal;
        return new EcsSerializationException(path + ": " + failure.getMessage(), failure);
    }
}
