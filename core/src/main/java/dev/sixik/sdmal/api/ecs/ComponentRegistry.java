package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.sync.NetworkComponent;
import dev.sixik.sdmal.core.ecs.serialization.NbtSerialization;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of {@link ComponentType}s, keyed by stable string id.
 *
 * <p>Fully concurrent: registration uses {@link ConcurrentHashMap#putIfAbsent} and lookups never
 * block. Registration is append-only during runtime; replacing an id requires an explicit {@link
 * #unregister(String)} so accidental codec collisions surface loudly instead of silently swapping
 * serializers.
 *
 * <p>Typical wiring uses {@link EcsComponent}; {@link #registerServices()} discovers indexed
 * component classes before worlds load. Manual {@link #register(ComponentType)} remains
 * available for dynamic component factories.
 */
public final class ComponentRegistry {

    /**
     * Shared stateless instance used by the public codec or registry accessor.
     */
    private static final ComponentRegistry INSTANCE = new ComponentRegistry();

    /**
     * Concurrent registry indexed by stable unique component identifiers.
     */
    private final Map<String, ComponentType<?>> byId = new ConcurrentHashMap<>();

    /**
     * Creates a ComponentRegistry with the supplied configuration.
     */
    private ComponentRegistry() {
    }

    /**
     * Process-wide singleton.
     *
     * @return shared component registry, never null
     */
    public static ComponentRegistry instance() {
        return INSTANCE;
    }

    /**
     * Registers components indexed by annotation processors in all visible mod JARs. Only provider
     * types are inspected; component instances are created later by their factories. SDMAL calls
     * this automatically during common setup, before worlds load.
     *
     * @return number of discovered component classes, including already registered classes
     * @throws java.util.ServiceConfigurationError if a service descriptor is malformed or a
     *                                             provider is invalid
     * @throws IllegalStateException               if different classes declare the same component id
     */
    public int registerServices() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return registerServices(
                loader != null ? loader : Component.class.getClassLoader());
    }

    /**
     * Registers indexed components visible through a specified mod or plugin class loader. Repeated
     * discovery of the same classes is idempotent. Provider constructors are never invoked.
     *
     * @param loader loader exposing the service descriptors and their component classes, non-null
     * @return number of discovered component classes, including already registered classes
     * @throws java.util.ServiceConfigurationError if a provider cannot be resolved
     * @throws IllegalArgumentException            if a provider lacks a valid {@link EcsComponent} annotation
     * @throws IllegalStateException               if different classes declare the same component id
     */
    public int registerServices(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        var providers = ServiceLoader.load(Component.class, loader).stream().iterator();
        int discovered = 0;
        while (providers.hasNext()) {
            registerAnnotated(providers.next().type());
            discovered++;
        }
        return discovered;
    }

    /**
     * Registers a type; throws if the id is already taken by another instance.
     *
     * @param <C>  component type
     * @param type component descriptor used for typed membership and duplicate policies
     * @return registered descriptor; the supplied instance when newly registered
     */
    public <C extends Component> ComponentType<C> register(
            ComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        NbtSerialization.validateComponent(type.componentClass());
        NetworkSerialization.validateComponent(type.componentClass());
        if (NetworkComponent.class.isAssignableFrom(type.componentClass())
                && (NbtSerialization.hasPersistedFields(type.componentClass())
                || NetworkSerialization.hasSyncedFields(type.componentClass())))
            throw new IllegalArgumentException(
                    "NetworkComponent uses field()/tracked(); use Component for @EcsPersist/@EcsSync: "
                            + type.componentClass().getName());
        ComponentType<?> existing = byId.putIfAbsent(type.id(), type);
        if (existing != null && existing != type) {
            throw new IllegalStateException("Duplicate component id: " + type.id());
        }
        return type;
    }

    /**
     * Registers an annotated component using its accessible no-argument constructor. Repeated
     * service discovery calls for the same class are idempotent.
     *
     * @param componentClass concrete annotated component class
     * @param <C>            component type
     * @return existing or newly created descriptor
     * @throws IllegalArgumentException if the annotation or constructor is invalid
     * @throws IllegalStateException    if the id belongs to another class
     */
    @SuppressWarnings("unchecked")
    public <C extends Component> ComponentType<C> registerAnnotated(
            Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        EcsComponent annotation = componentClass.getAnnotation(EcsComponent.class);
        if (annotation == null)
            throw new IllegalArgumentException(
                    "Missing @EcsComponent on " + componentClass.getName());
        if (componentClass.isInterface()
                || java.lang.reflect.Modifier.isAbstract(componentClass.getModifiers()))
            throw new IllegalArgumentException(
                    "ECS component must be concrete: " + componentClass.getName());
        final Constructor<C> constructor;
        try {
            constructor = componentClass.getDeclaredConstructor();
            if (!constructor.trySetAccessible())
                throw new IllegalArgumentException(
                        "Component constructor is inaccessible: " + componentClass.getName());
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException(
                    "@EcsComponent requires a no-argument constructor: " + componentClass.getName(),
                    failure);
        }
        ComponentType<?> existing = byId.get(annotation.id());
        if (existing != null) {
            if (existing.componentClass() != componentClass)
                throw new IllegalStateException("Duplicate component id: " + annotation.id());
            return (ComponentType<C>) existing;
        }
        ComponentType<C> type =
                new ComponentType<>(
                        annotation.id(),
                        componentClass,
                        annotation.duplicatePolicy(),
                        () -> {
                            try {
                                return constructor.newInstance();
                            } catch (InvocationTargetException failure) {
                                throw new EcsObjectException(
                                        "Annotated component constructor failed: "
                                                + componentClass.getName(),
                                        failure.getCause());
                            } catch (ReflectiveOperationException failure) {
                                throw new EcsObjectException(
                                        "Annotated component could not be constructed: "
                                                + componentClass.getName(),
                                        failure);
                            }
                        });
        return register(type);
    }

    /**
     * Looks up a descriptor by concrete component class.
     *
     * @param componentClass component class, non-null
     * @param <C>            component type
     * @return matching descriptor, or empty when not registered
     */
    @SuppressWarnings("unchecked")
    public <C extends Component> Optional<ComponentType<C>> get(
            Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        for (ComponentType<?> type : byId.values())
            if (type.componentClass() == componentClass)
                return Optional.of((ComponentType<C>) type);
        return Optional.empty();
    }

    /**
     * Returns a registered descriptor by class.
     *
     * @param componentClass component class, non-null
     * @param <C>            component type
     * @return registered descriptor
     * @throws IllegalStateException if service discovery or explicit registration has not run
     */
    public <C extends Component> ComponentType<C> require(
            Class<C> componentClass) {
        return get(componentClass)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Component is not registered: "
                                                + componentClass.getName()));
    }

    /**
     * Removes a registration. Returns the removed type, or empty if absent.
     *
     * @param id stable object UUID or registry identifier, non-null
     * @return matching value, or Optional.empty() when absent
     */
    public Optional<ComponentType<?>> unregister(String id) {
        return Optional.ofNullable(byId.remove(id));
    }

    /**
     * Looks up a type by id.
     *
     * @param id stable object UUID or registry identifier, non-null
     * @return matching value, or Optional.empty() when absent
     */
    public Optional<ComponentType<?>> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Looks up a type by id with a checked cast to the expected component class.
     *
     * @param <C>            component type
     * @param id             stable object UUID or registry identifier, non-null
     * @param componentClass expected component class, non-null
     * @return matching value, or Optional.empty() when absent
     */
    @SuppressWarnings("unchecked")
    public <C extends Component> Optional<ComponentType<C>> get(
            String id, Class<C> componentClass) {
        ComponentType<?> type = byId.get(id);
        if (type == null || !componentClass.isAssignableFrom(type.componentClass())) {
            return Optional.empty();
        }
        return Optional.of((ComponentType<C>) type);
    }

    /**
     * Snapshot of all registered types.
     *
     * @return immutable list of currently registered descriptors
     */
    public List<ComponentType<?>> all() {
        return new ObjectArrayList<>(byId.values());
    }

    /**
     * Number of registered types.
     *
     * @return current membership count, greater than or equal to zero
     */
    public int size() {
        return byId.size();
    }
}
