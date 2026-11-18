package dev.sixik.sdmal.api.repository;

import com.google.gson.reflect.TypeToken;

import dev.sixik.sdmal.internal.driver.gson.Gson;
import dev.sixik.sdmal.internal.driver.gson.GsonBuilder;
import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.core.repository.ComponentRepositoryCodec;
import net.minecraft.nbt.CompoundTag;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Converts repository values to and from their backend-neutral JSON representation.
 */
public interface RepositoryCodec<V> {
    /**
     * Creates a persistence codec for an exact registered ECS component class. Registry lookup is
     * deferred until read/write, so definitions may be declared before SDMAL common setup.
     * The envelope retains the component type id and dataVersion; old payloads use migrateNbt.
     * Component persistence methods select the payload, including EcsPersist and tracked fields.
     * Decoding creates a detached component without binding ownership or invoking attach callbacks.
     * Serialize attached values on their owner's thread; pass a detached snapshot to IO workers.
     *
     * @param type concrete component class registered before the codec is used
     * @param <C>  exact ECS component type
     * @return native NBT codec also usable in JSON, JDBC and MongoDB repositories
     * @throws IllegalArgumentException if type is abstract or still declares generic parameters
     * @throws NullPointerException     if type is null
     */
    static <C extends Component> NbtRepositoryCodec<C> component(Class<C> type) {
        return new ComponentRepositoryCodec<>(type);
    }

    /**
     * @return lossless NBT compound codec; use for detached ECS archives captured on their owner
     * thread
     */
    static NbtRepositoryCodec<CompoundTag> nbt() {
        return new NbtRepositoryCodec<>() {
            /**
             * @param value detached compound
             * @return independent compound
             */
            @Override
            public CompoundTag writeNbt(CompoundTag value) {
                return Objects.requireNonNull(value, "value").copy();
            }

            /**
             * @param tag decoded compound
             * @return independent compound
             */
            @Override
            public CompoundTag readNbt(CompoundTag tag) {
                return tag.copy();
            }
        };
    }

    /**
     * Encodes one non-null value as JSON.
     *
     * @param value value to encode
     * @return valid JSON text
     */
    String encode(V value);

    /**
     * Decodes one JSON value.
     *
     * @param json valid JSON text
     * @return decoded non-null value
     */
    V decode(String json);

    /**
     * Creates a Gson codec for a concrete value class.
     *
     * @param type value class
     * @param <V>  value type
     * @return immutable codec
     */
    static <V> RepositoryCodec<V> gson(Class<V> type) {
        return gson(TypeToken.get(Objects.requireNonNull(type, "type")));
    }

    /**
     * Creates a Gson codec for a generic value type.
     *
     * @param type generic value type token
     * @param <V>  value type
     * @return immutable codec
     */
    static <V> RepositoryCodec<V> gson(TypeToken<V> type) {
        Objects.requireNonNull(type, "type");
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        Type valueType = type.getType();
        return new RepositoryCodec<>() {
            /**
             * {@inheritDoc}
             */
            @Override
            public String encode(V value) {
                return gson.toJson(Objects.requireNonNull(value, "value"), valueType);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public V decode(String json) {
                return Objects.requireNonNull(gson.fromJson(json, valueType), "decoded value");
            }
        };
    }
}
