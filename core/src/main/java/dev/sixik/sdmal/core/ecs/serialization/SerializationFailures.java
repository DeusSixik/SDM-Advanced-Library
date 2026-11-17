package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;

import java.lang.reflect.InvocationTargetException;

/**
 * Internal exception translation for annotated schemas and adapters.
 */
final class SerializationFailures {
    /**
     * Prevents utility construction.
     */
    private SerializationFailures() {}

    /**
     * Adds a member path without concealing fatal JVM failures from constructors or adapters.
     *
     * @param path schema or value location
     * @param failure original invocation/access failure
     * @return contextual non-fatal exception
     */
    static EcsSerializationException at(String path, Throwable failure) {
        if (failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null) failure = invocation.getCause();
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (failure instanceof LinkageError fatal) throw fatal;
        return new EcsSerializationException(path + ": " + failure.getMessage(), failure);
    }
}
