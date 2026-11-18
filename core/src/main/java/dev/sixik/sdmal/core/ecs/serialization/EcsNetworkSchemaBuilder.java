package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.ComponentType;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.annotations.EcsUseNetworkAdapter;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Comparator;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stages finite binary schemas independently of NBT layouts and registrations.
 */
final class EcsNetworkSchemaBuilder {
    /**
     * Existing explicit binary registrations.
     */
    private final Map<Type, EcsNetworkSerializer<?>> registered;

    /**
     * Successful prior structural resolutions.
     */
    private final Map<Type, EcsNetworkSerializer<?>> resolved;

    /**
     * Successful prior per-field override instances.
     */
    private final Map<Class<? extends EcsNetworkSerializer<?>>, EcsNetworkSerializer<?>> overrides;

    /**
     * New binary adapters awaiting complete schema success.
     */
    private final Map<Type, EcsNetworkSerializer<?>> pending = new Object2ObjectLinkedOpenHashMap<>();

    /**
     * New annotation adapters awaiting complete schema success.
     */
    private final Map<Class<? extends EcsNetworkSerializer<?>>, EcsNetworkSerializer<?>>
            pendingOverrides = new Object2ObjectOpenHashMap<>();

    /**
     * Active resolution path, bounded to 64 levels and rejecting cycles.
     */
    private final Set<Type> visiting = new ObjectLinkedOpenHashSet<>();

    /**
     * Captures registry maps while the caller holds their monitor.
     *
     * @param registered exact explicit formats
     * @param resolved   successful prior resolutions
     * @param overrides  cached annotation adapters
     */
    EcsNetworkSchemaBuilder(
            Map<Type, EcsNetworkSerializer<?>> registered,
            Map<Type, EcsNetworkSerializer<?>> resolved,
            Map<Class<? extends EcsNetworkSerializer<?>>, EcsNetworkSerializer<?>> overrides) {
        this.registered = registered;
        this.resolved = resolved;
        this.overrides = overrides;
    }

    /**
     * Publishes adapters after the complete requested schema has been validated.
     *
     * @param target          resolved adapter map
     * @param targetOverrides annotation adapter map
     */
    void publish(
            Map<Type, EcsNetworkSerializer<?>> target,
            Map<Class<? extends EcsNetworkSerializer<?>>, EcsNetworkSerializer<?>>
                    targetOverrides) {
        target.putAll(pending);
        targetOverrides.putAll(pendingOverrides);
    }

    /**
     * Resolves native scalar, generic collection, enum or opted-in record formats.
     *
     * @param declared concrete type
     * @return checked non-null binary value adapter
     */
    EcsNetworkSerializer<?> resolve(Type declared) {
        EcsTypes.requireConcrete(declared);
        Type type = EcsTypes.boxed(declared);
        var known = pending.get(type);
        if (known != null) return known;
        known = resolved.get(type);
        if (known != null) return known;
        if (visiting.size() >= 64 || !visiting.add(type))
            throw new EcsSerializationException(
                    "Recursive or excessively deep ECS wire schema: " + type);
        try {
            var adapter = registered.get(type);
            if (adapter == null) {
                Class<?> raw = EcsTypes.raw(type);
                adapter = Component.class.isAssignableFrom(raw)
                        ? componentValue(raw)
                        : EcsNetworkStructures.build(type, this);
            }
            var result = checked(type, adapter);
            pending.put(type, result);
            return result;
        } finally {
            visiting.remove(type);
        }
    }

    /**
     * Resolves a packet field containing a concrete, registered component. The component's own
     * framed codec selects only its EcsSync fields; the registry factory reconstructs the detached
     * instance on the receiving side.
     *
     * @param raw concrete component class declared by the packet field
     * @return component value adapter tied to its stable registry id and wire schema
     */
    private EcsNetworkSerializer<?> componentValue(Class<?> raw) {
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()))
            throw new EcsSerializationException(
                    "Packet component fields require a concrete component type: " + raw.getName());
        ComponentType<?> descriptor = registeredComponent(raw);
        long fingerprint = NetworkSerialization.schemaFingerprint(descriptor.componentClass());
        String schema = "ecs-component/" + descriptor.id() + "/" + Long.toUnsignedString(fingerprint, 16);
        return EcsNetworkBuiltins.of(
                schema,
                (buffer, value) -> {
                    if (value.getClass() != raw)
                        throw new EcsSerializationException(
                                "Expected exact packet component type " + raw.getName());
                    ((Component) value).writeNetwork(buffer);
                },
                buffer -> {
                    ComponentType<?> current = ComponentRegistry.instance()
                            .get(descriptor.id())
                            .filter(candidate -> candidate.componentClass() == raw)
                            .orElseThrow(() -> new EcsSerializationException(
                                    "Packet component is not registered: " + descriptor.id()));
                    Component component = current.createDefault();
                    if (component.getClass() != raw || component.isAttached())
                        throw new EcsSerializationException(
                                "Component factory must return a fresh " + raw.getName());
                    component.readNetwork(buffer);
                    return component;
                });
    }

    /**
     * Looks up a component descriptor by its exact concrete class.
     *
     * @param raw concrete component class
     * @return registered descriptor
     */
    @SuppressWarnings("unchecked")
    private static ComponentType<?> registeredComponent(Class<?> raw) {
        Class<? extends Component> componentClass = (Class<? extends Component>) raw;
        return ComponentRegistry.instance().get(componentClass)
                .orElseThrow(() -> new EcsSerializationException(
                        "Packet component type is not registered: " + raw.getName()));
    }

    /**
     * Selects only EcsSync fields and resolves inherited concrete generic arguments.
     *
     * @param type concrete annotated component class
     * @return complete key-sorted wire schema
     */
    EcsNetworkSchema component(Class<?> type) {
        if (!Component.class.isAssignableFrom(type)
                || Modifier.isAbstract(type.getModifiers()))
            throw new EcsSerializationException("Expected concrete Component");
        List<EcsNetworkMember> members = new ObjectArrayList<>();
        Set<String> keys = new ObjectLinkedOpenHashSet<>();
        Type current = type;
        while (EcsTypes.raw(current) != Component.class) {
            Class<?> raw = EcsTypes.raw(current);
            var bindings = EcsTypes.bindings(current);
            for (Field field : raw.getDeclaredFields()) {
                if (!field.isAnnotationPresent(EcsSync.class)) {
                    if (field.isAnnotationPresent(EcsUseNetworkAdapter.class))
                        throw new EcsSerializationException(
                                field + ": @EcsUseNetworkAdapter requires @EcsSync");
                    continue;
                }
                if (Modifier.isFinal(field.getModifiers())
                        || Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers())
                        || field.isSynthetic())
                    throw new EcsSerializationException(
                            field + ": synced component fields must be mutable instance fields");
                var member = member(field, EcsTypes.substitute(field.getGenericType(), bindings));
                if (!keys.add(member.key()))
                    throw new EcsSerializationException("Duplicate synced key: " + member.key());
                members.add(member);
            }
            current = EcsTypes.substitute(raw.getGenericSuperclass(), bindings);
        }
        members.sort(Comparator.comparing(EcsNetworkMember::key));
        if (members.size() > 4096)
            throw new EcsSerializationException("Too many synced component fields");
        String[] descriptions = new String[members.size()];
        for (int index = 0; index < descriptions.length; index++)
            descriptions[index] = members.get(index).schemaName();
        return new EcsNetworkSchema(
                members.toArray(EcsNetworkMember[]::new),
                EcsWireSchema.fingerprint(EcsWireSchema.describe("component/v1", descriptions)));
    }

    /**
     * Resolves an explicit wire key or reuses a member's stable persistence key. For a record all
     * constructor components are encoded; a persistence key is sufficient there.
     *
     * @param field selected component field or record backing field
     * @param type  concrete member value type
     * @return cached field/adapter plan
     */
    EcsNetworkMember member(Field field, Type type) {
        return member(field, type, false);
    }

    /**
     * Resolves a field, allowing its Java name as the default key for packet payloads.
     *
     * @param field       instance field
     * @param type        resolved field type
     * @param implicitKey whether an unannotated Java name is permitted
     * @return immutable binary member plan
     */
    EcsNetworkMember member(Field field, Type type, boolean implicitKey) {
        String path = field.getDeclaringClass().getName() + "." + field.getName();
        try {
            EcsSync sync = field.getAnnotation(EcsSync.class);
            EcsPersist persist = field.getAnnotation(EcsPersist.class);
            String key =
                    sync == null || sync.value().isEmpty()
                            ? persist == null ? "" : persist.value()
                            : sync.value();
            if (key.isEmpty() && implicitKey) key = field.getName();
            if (key.isBlank() || key.length() > 1024)
                throw new EcsSerializationException("Explicit stable network key is required");
            EcsTypes.requireConcrete(type);
            var override = field.getAnnotation(EcsUseNetworkAdapter.class);
            var serializer = override == null
                    ? implicitKey && type == byte[].class ? PacketBytes.FORMAT : resolve(type)
                    : override(type, override.value());
            var lookup =
                    MethodHandles.privateLookupIn(
                            field.getDeclaringClass(), MethodHandles.lookup());
            var handle =
                    lookup.findVarHandle(
                            field.getDeclaringClass(), field.getName(), field.getType());
            return new EcsNetworkMember(
                    key,
                    path,
                    handle,
                    serializer,
                    !EcsTypes.raw(type).isPrimitive(),
                    Modifier.isVolatile(field.getModifiers()),
                    override == null ? EcsNetworkMember.primitiveKind(field.getType()) : 0);
        } catch (Throwable failure) {
            throw SerializationFailures.at(path, failure);
        }
    }

    /**
     * Instantiates and checks a native per-member override.
     *
     * @param type         exact member type
     * @param adapterClass no-argument native adapter class
     * @return checked adapter
     * @throws ReflectiveOperationException if its constructor cannot be called
     */
    private EcsNetworkSerializer<?> override(
            Type type, Class<? extends EcsNetworkSerializer<?>> adapterClass)
            throws ReflectiveOperationException {
        Type argument = EcsTypes.adapterValue(adapterClass, EcsNetworkSerializer.class);
        if (argument == null || !EcsTypes.boxed(type).equals(EcsTypes.boxed(argument)))
            throw new EcsSerializationException(
                    "Network adapter has incompatible value type: " + adapterClass.getName());
        var adapter = overrides.get(adapterClass);
        if (adapter == null) adapter = pendingOverrides.get(adapterClass);
        if (adapter == null) {
            var constructor = adapterClass.getDeclaredConstructor();
            if (!constructor.trySetAccessible())
                throw new IllegalAccessException("Network adapter constructor is inaccessible");
            adapter = constructor.newInstance();
            pendingOverrides.put(adapterClass, adapter);
        }
        return checked(type, adapter);
    }

    /**
     * Freezes an adapter's stable wire name and validates all value boundaries.
     *
     * @param type    declared exact type
     * @param adapter native format implementation
     * @return immutable checked wrapper
     */
    @SuppressWarnings("unchecked")
    static EcsNetworkSerializer<?> checked(Type type, EcsNetworkSerializer<?> adapter) {
        String schema = adapter.schemaName();
        if (schema == null || schema.isBlank())
            throw new EcsSerializationException("Network adapter requires a stable schemaName");
        Class<?> raw = EcsTypes.raw(EcsTypes.boxed(type));
        var delegate = (EcsNetworkSerializer<Object>) adapter;
        return EcsNetworkBuiltins.of(
                schema,
                (buffer, value) -> {
                    if (value == null || !raw.isInstance(value))
                        throw new EcsSerializationException(
                                "Expected non-null " + type.getTypeName());
                    delegate.write(buffer, value);
                },
                buffer -> {
                    Object value = delegate.read(buffer);
                    if (value == null || !raw.isInstance(value))
                        throw new EcsSerializationException(
                                "Network adapter returned incompatible " + type.getTypeName());
                    return value;
                });
    }
}
