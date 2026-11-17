package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.core.ecs.AnnotatedWorldObjectComponent;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stages complete finite schemas under the registry monitor; failed validation does not freeze dependencies.
 */
final class EcsSchemaBuilder {
    /**
     * Existing exact registrations; never mutated by this builder.
     */
    private final Map<Type, EcsSerializer<?>> registered;
    /**
     * Existing resolved adapters; never mutated before publication.
     */
    private final Map<Type, EcsSerializer<?>> resolved;
    /**
     * Existing no-argument annotation adapter instances.
     */
    private final Map<Class<? extends EcsSerializer<?>>, EcsSerializer<?>> overrides;
    /**
     * Newly built adapters awaiting a successful whole-schema publication.
     */
    private final Map<Type, EcsSerializer<?>> pending = new LinkedHashMap<>();
    /**
     * Newly constructed annotation overrides awaiting publication.
     */
    private final Map<Class<? extends EcsSerializer<?>>, EcsSerializer<?>> pendingOverrides = new HashMap<>();
    /**
     * Active resolution chain, capped at 64 and used to reject recursive schemas.
     */
    private final Set<Type> visiting = new LinkedHashSet<>();

    /**
     * Captures stable registry maps while the caller holds the registry monitor.
     *
     * @param registered explicit/built-in adapters
     * @param resolved   successful prior resolutions
     * @param overrides  successful prior annotation adapter instances
     */
    EcsSchemaBuilder(Map<Type, EcsSerializer<?>> registered, Map<Type, EcsSerializer<?>> resolved,
                     Map<Class<? extends EcsSerializer<?>>, EcsSerializer<?>> overrides) {
        this.registered = registered;
        this.resolved = resolved;
        this.overrides = overrides;
    }

    /**
     * Commits completed plans only after validation of the entire root succeeds.
     *
     * @param target          global resolved adapter map
     * @param targetOverrides global annotation adapter map
     */
    void publish(Map<Type, EcsSerializer<?>> target,
                 Map<Class<? extends EcsSerializer<?>>, EcsSerializer<?>> targetOverrides) {
        target.putAll(pending);
        targetOverrides.putAll(pendingOverrides);
    }

    /**
     * Resolves registered scalars, exact generic collections, enums or opted-in records.
     *
     * @param declared concrete value type
     * @return checked non-null value adapter
     */
    EcsSerializer<?> resolve(Type declared) {
        EcsTypes.requireConcrete(declared);
        Type type = EcsTypes.boxed(declared);
        EcsSerializer<?> known = pending.get(type);
        if (known != null) return known;
        known = resolved.get(type);
        if (known != null) return known;
        if (visiting.size() >= 64 || !visiting.add(type))
            throw new EcsSerializationException("Recursive or excessively deep ECS schema: " + visiting + " -> " + type);
        try {
            EcsSerializer<?> adapter = registered.get(type);
            if (adapter == null) adapter = EcsStructuredSerializers.build(type, this);
            EcsSerializer<?> checked = checked(type, adapter);
            pending.put(type, checked);
            return checked;
        } finally {
            visiting.remove(type);
        }
    }

    /**
     * Builds persisted fields across a component's concrete generic superclass chain.
     *
     * @param type concrete annotated component implementation
     * @return immutable schema in stable NBT-key order
     */
    EcsComponentSchema component(Class<?> type) {
        if (!AnnotatedWorldObjectComponent.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers()))
            throw new EcsSerializationException("Expected concrete AnnotatedWorldObjectComponent");
        List<EcsMember> members = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Type current = type;
        while (EcsTypes.raw(current) != AnnotatedWorldObjectComponent.class) {
            Class<?> raw = EcsTypes.raw(current);
            var bindings = EcsTypes.bindings(current);
            for (Field field : raw.getDeclaredFields()) {
                if (!field.isAnnotationPresent(EcsPersist.class)) {
                    if (field.isAnnotationPresent(EcsUseAdapter.class))
                        throw new EcsSerializationException(field + ": @EcsUseAdapter requires @EcsPersist");
                    continue;
                }
                if (Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers()) || field.isSynthetic())
                    throw new EcsSerializationException(field + ": persisted component fields must be mutable instance fields");
                EcsMember member = member(field, EcsTypes.substitute(field.getGenericType(), bindings));
                if (!keys.add(member.key()))
                    throw new EcsSerializationException("Duplicate persisted key: " + member.key());
                members.add(member);
            }
            current = EcsTypes.substitute(raw.getGenericSuperclass(), bindings);
        }
        members.sort(Comparator.comparing(EcsMember::key));
        return new EcsComponentSchema(List.copyOf(members));
    }

    /**
     * Creates a cached private-field handle and resolves its exact serializer or explicit override.
     *
     * @param field annotated component field or record backing field
     * @param type  resolved generic value type
     * @return immutable access plan
     */
    EcsMember member(Field field, Type type) {
        String path = field.getDeclaringClass().getName() + "." + field.getName();
        try {
            String key = field.getAnnotation(EcsPersist.class).value();
            if (key.isBlank() || key.length() > 1024) throw new EcsSerializationException("Invalid persisted key");
            EcsTypes.requireConcrete(type);
            EcsUseAdapter use = field.getAnnotation(EcsUseAdapter.class);
            EcsSerializer<?> serializer = use == null ? resolve(type) : override(type, use.value());
            var lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            var access = lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
            return new EcsMember(key, path, access, type, serializer, Modifier.isVolatile(field.getModifiers()));
        } catch (Throwable failure) {
            throw EcsSerializationException.at(path, failure);
        }
    }

    /**
     * Instantiates a stateless no-argument field override and validates its declared generic value type.
     *
     * @param type         member's concrete type
     * @param adapterClass explicit serializer implementation
     * @return checked adapter shared across uses of this adapter class
     * @throws ReflectiveOperationException if its no-argument constructor cannot be invoked
     */
    private EcsSerializer<?> override(Type type, Class<? extends EcsSerializer<?>> adapterClass)
            throws ReflectiveOperationException {
        Type valueType = EcsTypes.adapterValue(adapterClass);
        if (valueType == null || !EcsTypes.boxed(type).equals(EcsTypes.boxed(valueType)))
            throw new EcsSerializationException("Adapter " + adapterClass.getName() + " does not declare EcsSerializer<" + type.getTypeName() + ">");
        EcsSerializer<?> adapter = overrides.get(adapterClass);
        if (adapter == null) adapter = pendingOverrides.get(adapterClass);
        if (adapter == null) {
            var constructor = adapterClass.getDeclaredConstructor();
            if (!constructor.trySetAccessible())
                throw new IllegalAccessException("Adapter constructor is inaccessible");
            adapter = constructor.newInstance();
            pendingOverrides.put(adapterClass, adapter);
        }
        return checked(type, adapter);
    }

    /**
     * Checks custom adapters' value/tag contracts at every boundary, including nested generic elements.
     *
     * @param type    exact declared value type
     * @param adapter resolved unchecked adapter
     * @return checked adapter
     */
    @SuppressWarnings("unchecked")
    private static EcsSerializer<?> checked(Type type, EcsSerializer<?> adapter) {
        Class<?> raw = EcsTypes.raw(EcsTypes.boxed(type));
        EcsSerializer<Object> delegate = (EcsSerializer<Object>) adapter;
        return EcsBuiltinSerializers.of(value -> {
            if (value == null || !raw.isInstance(value))
                throw new EcsSerializationException("Expected non-null " + type.getTypeName());
            var tag = delegate.write(value);
            if (tag == null || tag instanceof net.minecraft.nbt.EndTag)
                throw new EcsSerializationException("Adapter returned an invalid NBT payload for " + type.getTypeName());
            return tag;
        }, tag -> {
            Object value = delegate.read(java.util.Objects.requireNonNull(tag, "tag"));
            if (value == null || !raw.isInstance(value))
                throw new EcsSerializationException("Adapter returned incompatible " + type.getTypeName());
            return value;
        });
    }
}
