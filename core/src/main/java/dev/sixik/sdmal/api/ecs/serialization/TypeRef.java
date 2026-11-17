package dev.sixik.sdmal.api.ecs.serialization;

import dev.sixik.sdmal.core.ecs.serialization.NbtSerialization;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures a concrete generic value type, for example {@code new TypeRef<List<UUID>>() {}}. Raw
 * collections, unresolved variables and wildcards cannot describe a persistent schema.
 *
 * @param <T> captured value type
 */
public abstract class TypeRef<T> {
    /**
     * Concrete reflection type retained from the direct parameterized superclass.
     */
    private final Type type;

    /**
     * Captures the direct subclass's type argument.
     *
     * @throws IllegalArgumentException if the subclass does not directly specialize TypeRef
     */
    protected TypeRef() {
        if (!(getClass().getGenericSuperclass() instanceof ParameterizedType parent)
                || parent.getRawType() != TypeRef.class)
            throw new IllegalArgumentException(
                    "Use a direct TypeRef<T> subclass with concrete type arguments");
        type = parent.getActualTypeArguments()[0];
        NbtSerialization.validateValueType(type);
    }

    /**
     * @return captured concrete type, including nested generic arguments
     */
    public final Type type() {
        return type;
    }
}
