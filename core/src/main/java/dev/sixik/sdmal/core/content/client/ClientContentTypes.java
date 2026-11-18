package dev.sixik.sdmal.core.content.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;

/**
 * Startup-only generic validation for screens and entity renderers, including inherited bindings.
 */
final class ClientContentTypes {
    /**
     * Prevents construction.
     */
    private ClientContentTypes() {}

    /**
     * Checks the first generic parameter of a client base class against the registered common type.
     *
     * @param implementation concrete client factory class
     * @param base AbstractContainerScreen or EntityRenderer
     * @param actual registered menu or entity implementation
     */
    static void requireCompatible(Class<?> implementation, Class<?> base, Class<?> actual) {
        Map<TypeVariable<?>, Type> bindings = new Object2ObjectOpenHashMap<>();
        for (Type current = implementation; current != null;) {
            Class<?> raw;
            if (current instanceof ParameterizedType parameterized) {
                raw = (Class<?>) parameterized.getRawType();
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int index = 0; index < variables.length; index++) {
                    bindings.put(variables[index], resolve(arguments[index], bindings));
                }
            } else if (current instanceof Class<?> type) {
                raw = type;
            } else {
                break;
            }
            if (raw == base) {
                Type accepted = resolve(raw.getTypeParameters()[0], bindings);
                if (accepted instanceof Class<?> type && type.isAssignableFrom(actual)) {
                    return;
                }
                throw new IllegalArgumentException("Client content " + implementation.getName()
                        + " has incompatible or unresolved " + base.getSimpleName() + " type for " + actual.getName());
            }
            current = raw.getGenericSuperclass();
        }
        throw new IllegalArgumentException("Missing client generic base on " + implementation.getName());
    }

    /**
     * Substitutes inherited type variables while avoiding self-referential bounds.
     *
     * @param source reflected generic argument
     * @param bindings already resolved superclass mappings
     * @return resolved class/type or original unresolved variable
     */
    private static Type resolve(Type source, Map<TypeVariable<?>, Type> bindings) {
        Type current = source;
        while (current instanceof TypeVariable<?> variable) {
            Type replacement = bindings.get(variable);
            if (replacement == null || replacement == current) {
                return current;
            }
            current = replacement;
        }
        return current;
    }
}
