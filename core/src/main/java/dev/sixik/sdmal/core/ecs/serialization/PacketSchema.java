package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.Comparator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * Automatic class/record field plans for packets, independent of ECS component selection.
 */
final class PacketSchema {
    /**
     * Prevents utility construction.
     */
    private PacketSchema() {
    }

    /**
     * Resolves every instance field once; ordinary classes require mutable fields and a no-arg
     * constructor.
     *
     * @param type    concrete packet type
     * @param builder shared binary adapter resolution transaction
     * @param <T>     packet type
     * @return direct binary codec with a checked schema fingerprint
     */
    static <T> EcsNetworkSerializer<T> build(Class<T> type, EcsNetworkSchemaBuilder builder) {
        if (Modifier.isAbstract(type.getModifiers()) || type.isInterface())
            throw new EcsSerializationException("Packet must be concrete: " + type.getName());
        try {
            var plans = new ObjectArrayList<EcsNetworkMember>();
            var keys = new ObjectOpenHashSet<String>();
            Type current = type;
            while (EcsTypes.raw(current) != Object.class && EcsTypes.raw(current) != Record.class) {
                var raw = EcsTypes.raw(current);
                var bindings = EcsTypes.bindings(current);
                for (var field : raw.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers)
                            || Modifier.isTransient(modifiers)
                            || field.isSynthetic()) continue;
                    if (!type.isRecord() && Modifier.isFinal(modifiers))
                        throw new EcsSerializationException(
                                field + ": final packet fields require a record");
                    var plan =
                            builder.member(
                                    field,
                                    EcsTypes.substitute(field.getGenericType(), bindings),
                                    true);
                    if (!keys.add(plan.key()))
                        throw new EcsSerializationException("Duplicate packet key: " + plan.key());
                    plans.add(plan);
                }
                current = EcsTypes.substitute(raw.getGenericSuperclass(), bindings);
            }
            if (plans.size() > 4096) throw new EcsSerializationException("Too many packet fields");
            plans.sort(Comparator.comparing(EcsNetworkMember::key));
            var members = plans.toArray(EcsNetworkMember[]::new);
            var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            Class<?>[] parameters =
                    type.isRecord()
                            ? Arrays.stream(type.getRecordComponents())
                            .map(RecordComponent::getType)
                            .toArray(Class<?>[]::new)
                            : new Class<?>[0];
            var constructor =
                    lookup.findConstructor(type, MethodType.methodType(void.class, parameters))
                            .asSpreader(Object[].class, parameters.length)
                            .asType(MethodType.methodType(Object.class, Object[].class));
            int[] slots = new int[members.length];
            if (type.isRecord()) {
                var components = type.getRecordComponents();
                for (int index = 0; index < members.length; index++) {
                    for (int slot = 0; slot < components.length; slot++) {
                        if (members[index]
                                .path()
                                .equals(type.getName() + "." + components[slot].getName()))
                            slots[index] = slot;
                    }
                }
            }
            var descriptions = new String[members.length];
            for (int index = 0; index < members.length; index++)
                descriptions[index] = members[index].schemaName();
            String schema = EcsWireSchema.describe("packet-fields/v1", descriptions);
            long fingerprint = EcsWireSchema.fingerprint(schema);
            return EcsNetworkBuiltins.of(
                    schema,
                    (buffer, value) -> {
                        buffer.writeLong(fingerprint);
                        for (int index = 0; index < members.length; index++)
                            members[index].write(value, buffer);
                    },
                    buffer -> {
                        if (buffer.readLong() != fingerprint)
                            throw new EcsSerializationException(
                                    "Incompatible packet schema: " + type.getName());
                        try {
                            if (type.isRecord()) {
                                Object[] arguments = new Object[members.length];
                                for (int index = 0; index < members.length; index++)
                                    arguments[slots[index]] = members[index].read(buffer);
                                return type.cast((Object) constructor.invokeExact(arguments));
                            }
                            T value = type.cast((Object) constructor.invokeExact(new Object[0]));
                            for (int index = 0; index < members.length; index++)
                                members[index].set(value, members[index].read(buffer));
                            return value;
                        } catch (Throwable failure) {
                            throw SerializationFailures.at(type.getName(), failure);
                        }
                    });
        } catch (ReflectiveOperationException failure) {
            throw SerializationFailures.at(type.getName(), failure);
        }
    }
}
