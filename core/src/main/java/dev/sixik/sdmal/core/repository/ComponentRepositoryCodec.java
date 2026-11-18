package dev.sixik.sdmal.core.repository;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.ComponentType;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.repository.NbtRepositoryCodec;
import dev.sixik.sdmal.api.repository.RepositoryException;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Persists one registered component in a versioned envelope using its existing ECS NBT contract.
 * No reflection serializer or independent component factory is introduced.
 * @param <C> exact declared component type
 */
@ApiStatus.Internal
public final class ComponentRepositoryCodec<C extends Component> implements NbtRepositoryCodec<C> {
    /**
     * Envelope version, independent of the component's persisted schema version.
     */
    private static final int FORMAT = 1;

    /**
     * Concrete class supplied by the application; runtime subclasses are not accepted.
     */
    private final Class<C> componentClass;

    /**
     * Validates the declared type without creating a component or touching the global registry.
     * @param componentClass concrete component class without unbound type parameters
     * @throws IllegalArgumentException if the declared type cannot identify an exact component
     */
    public ComponentRepositoryCodec(Class<C> componentClass) {
        this.componentClass = Objects.requireNonNull(componentClass, "componentClass");
        if (!Component.class.isAssignableFrom(componentClass)
                || Modifier.isAbstract(componentClass.getModifiers())
                || componentClass.isInterface()
                || componentClass.getTypeParameters().length != 0)
            throw new IllegalArgumentException("Expected concrete component class: " + componentClass.getName());
    }

    /**
     * Captures only the component's persisted payload, with no object identity or live ownership.
     * @param value exact component instance; caller coordinates detached mutable values
     * @return owned envelope containing format, type, version and data
     * @throws IllegalStateException if an attached component is accessed outside its owner thread
     * @throws RepositoryException if the type is unregistered, invalid or cannot be serialized
     */
    @Override
    public CompoundTag writeNbt(C value) {
        Objects.requireNonNull(value, "value");
        requireExactType(value);
        value.owner().flatMap(owner -> owner.manager()).ifPresent(EcsObjectManager::checkThread);
        ComponentType<C> descriptor = descriptor();
        try {
            int version = currentVersion(value);
            CompoundTag data = new CompoundTag();
            value.writeNbt(data);
            CompoundTag envelope = new CompoundTag();
            envelope.putInt("format", FORMAT);
            envelope.putString("type", descriptor.id());
            envelope.putInt("version", version);
            envelope.put("data", data.copy());
            return envelope;
        } catch (RepositoryException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RepositoryException("Cannot persist component " + descriptor.id(), failure);
        }
    }

    /**
     * Decodes and migrates independently owned data before returning an unpublished component.
     * @param envelope stored envelope; never mutated or retained by the codec
     * @return exact detached component created by its registered factory
     * @throws RepositoryException for an unknown format, wrong type, future schema or invalid factory
     */
    @Override
    public C readNbt(CompoundTag envelope) {
        Objects.requireNonNull(envelope, "envelope");
        ComponentType<C> descriptor = descriptor();
        try {
            if (integer(envelope, "format") != FORMAT)
                throw new RepositoryException("Unsupported component repository format");
            if (!(envelope.get("type") instanceof StringTag type)
                    || !descriptor.id().equals(NbtAccess.string(type, "")))
                throw new RepositoryException("Repository component type does not match " + descriptor.id());
            int savedVersion = integer(envelope, "version");
            if (!(envelope.get("data") instanceof CompoundTag data))
                throw new RepositoryException("Missing repository component payload");

            C component = descriptor.createDefault();
            requireExactType(component);
            requireDetached(component);
            int currentVersion = currentVersion(component);
            if (savedVersion > currentVersion)
                throw new RepositoryException("Unsupported future component version: " + savedVersion);
            CompoundTag payload = data.copy();
            if (savedVersion < currentVersion) {
                payload = Objects.requireNonNull(
                        component.migrateNbt(savedVersion, payload),
                        "component migration returned null").copy();
            }
            component.readNbt(payload);
            requireDetached(component);
            return component;
        } catch (RepositoryException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RepositoryException("Cannot restore component " + descriptor.id(), failure);
        }
    }

    /**
     * Resolves the current exact registration without retaining descriptors after unregistration.
     * @return descriptor discovered during SDMAL setup or registered explicitly by the application
     * @throws RepositoryException if the component has not been registered
     */
    private ComponentType<C> descriptor() {
        return ComponentRegistry.instance().get(componentClass).orElseThrow(
                () -> new RepositoryException("Component is not registered: " + componentClass.getName()));
    }

    /**
     * Rejects implicit subclass serialization under a different registered schema.
     * @param value instance supplied by the caller or component factory
     * @throws RepositoryException if the runtime type is not the declared class
     */
    private void requireExactType(Component value) {
        if (value.getClass() == componentClass) return;
        throw new RepositoryException("Expected exact component type: " + componentClass.getName());
    }

    /**
     * Verifies that a factory or read override did not publish ownership as a side effect.
     * @param value freshly reconstructed component
     * @throws RepositoryException if the component is already attached
     */
    private static void requireDetached(Component value) {
        if (!value.isAttached()) return;
        throw new RepositoryException("Component factory/readNbt must return a detached component");
    }

    /**
     * Reads the current nonnegative persisted component schema version.
     * @param value source or newly constructed component
     * @return current persisted schema version
     * @throws RepositoryException if the component declares a negative version
     */
    private static int currentVersion(Component value) {
        int version = value.dataVersion();
        if (version >= 0) return version;
        throw new RepositoryException("Negative component data version");
    }

    /**
     * Reads strict nonnegative integer metadata across Minecraft versions.
     * @param envelope stored envelope
     * @param key required metadata property
     * @return nonnegative integer
     * @throws RepositoryException if metadata is missing, has a wrong NBT type or is negative
     */
    private static int integer(CompoundTag envelope, String key) {
        if (!(envelope.get(key) instanceof IntTag tag))
            throw new RepositoryException("Expected component repository int: " + key);
        int value = NbtAccess.number(tag).intValue();
        if (value >= 0) return value;
        throw new RepositoryException("Negative component repository metadata: " + key);
    }
}
