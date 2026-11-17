package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves concrete record/member/adapter type arguments without erasing collection element types.
 */
final class EcsTypes {
    /**
     * Prevents utility construction.
     */
    private EcsTypes() {
    }

    /**
     * Validates a finite concrete type expression.
     *
     * @param type value type
     * @throws IllegalArgumentException for raw generic classes, wildcards or unresolved variables
     */
    static void requireConcrete(Type type) {
        if (type instanceof Class<?> raw) {
            if (raw.getTypeParameters().length != 0)
                throw new IllegalArgumentException("Raw generic type: " + type);
        } else if (type instanceof ParameterizedType generic
                && generic.getRawType() instanceof Class<?>) {
            for (Type argument : generic.getActualTypeArguments()) requireConcrete(argument);
        } else throw new IllegalArgumentException("Expected concrete type, got " + type);
    }

    /**
     * Extracts a supported class token.
     *
     * @param type class or parameterized class
     * @return raw class
     * @throws IllegalArgumentException for other type expressions
     */
    static Class<?> raw(Type type) {
        if (type instanceof Class<?> value) return value;
        if (type instanceof ParameterizedType generic
                && generic.getRawType() instanceof Class<?> value) return value;
        throw new IllegalArgumentException("Unsupported type " + type);
    }

    /**
     * Normalizes scalar primitives to their registered wrapper types.
     *
     * @param type concrete type
     * @return boxed scalar class or unchanged reference type
     */
    static Type boxed(Type type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }

    /**
     * Substitutes bound variables in a field, record component or superclass signature.
     *
     * @param type     declared type expression
     * @param bindings variables bound by the containing type
     * @return resolved type expression, possibly still unresolved when no binding exists
     */
    static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable)
            return bindings.getOrDefault(variable, variable);
        if (type instanceof ParameterizedType generic) {
            Type[] arguments = generic.getActualTypeArguments();
            for (int index = 0; index < arguments.length; index++)
                arguments[index] = substitute(arguments[index], bindings);
            Type owner = generic.getOwnerType();
            return new Applied(
                    generic.getRawType(),
                    owner == null ? null : substitute(owner, bindings),
                    arguments);
        }
        return type;
    }

    /**
     * Maps one containing class's variables to its actual arguments.
     *
     * @param type concrete containing type
     * @return independent substitution map
     */
    static Map<TypeVariable<?>, Type> bindings(Type type) {
        Map<TypeVariable<?>, Type> result = new HashMap<>();
        if (type instanceof ParameterizedType generic) {
            TypeVariable<?>[] variables = raw(type).getTypeParameters();
            Type[] arguments = generic.getActualTypeArguments();
            for (int index = 0; index < variables.length; index++)
                result.put(variables[index], arguments[index]);
        }
        return result;
    }

    /**
     * Finds the serializer's generic value type through inherited interfaces/classes.
     *
     * @param type adapter class or specialized parent
     * @return concrete serializer argument, or null when no serializer specialization exists
     */
    static Type adapterValue(Type type) {
        return adapterValue(type, EcsSerializer.class);
    }

    /**
     * Resolves a concrete value argument for either NBT or network adapter interfaces.
     *
     * @param type             specialized adapter implementation/parent
     * @param adapterInterface single-argument target serializer interface
     * @return resolved argument or null if the interface is absent/raw
     */
    static Type adapterValue(Type type, Class<?> adapterInterface) {
        Class<?> raw = raw(type);
        if (raw == adapterInterface)
            return type instanceof ParameterizedType generic
                    ? generic.getActualTypeArguments()[0]
                    : null;
        var bindings = bindings(type);
        for (Type parent : raw.getGenericInterfaces()) {
            Type found = adapterValue(substitute(parent, bindings), adapterInterface);
            if (found != null) return found;
        }
        Type parent = raw.getGenericSuperclass();
        return parent == null ? null : adapterValue(substitute(parent, bindings), adapterInterface);
    }

    /**
     * Immutable parameterized type with the JDK's structural equality/hash contract.
     */
    private static final class Applied implements ParameterizedType {
        /**
         * Raw class being specialized.
         */
        private final Type raw;

        /**
         * Containing class, or null for top-level declarations.
         */
        private final Type owner;

        /**
         * Owned generic arguments in declaration order.
         */
        private final Type[] arguments;

        /**
         * Captures a resolved parameterized signature.
         *
         * @param raw       raw class
         * @param owner     containing type, possibly null
         * @param arguments actual arguments
         */
        private Applied(Type raw, Type owner, Type[] arguments) {
            this.raw = raw;
            this.owner = owner;
            this.arguments = arguments.clone();
        }

        /**
         * @return defensive copy of actual arguments
         */
        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        /**
         * @return raw class
         */
        @Override
        public Type getRawType() {
            return raw;
        }

        /**
         * @return containing type or null
         */
        @Override
        public Type getOwnerType() {
            return owner;
        }

        /**
         * Compares structurally with any ParameterizedType implementation.
         *
         * @param other signature to compare
         * @return true for equal raw/owner/argument signatures
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType generic
                    && raw.equals(generic.getRawType())
                    && java.util.Objects.equals(owner, generic.getOwnerType())
                    && Arrays.equals(arguments, generic.getActualTypeArguments());
        }

        /**
         * @return structural hash compatible with JDK parameterized signatures
         */
        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ raw.hashCode() ^ java.util.Objects.hashCode(owner);
        }

        /**
         * @return readable raw class and arguments for diagnostics
         */
        @Override
        public String getTypeName() {
            return raw.getTypeName() + Arrays.toString(arguments);
        }

        /**
         * @return diagnostic type name
         */
        @Override
        public String toString() {
            return getTypeName();
        }
    }
}
