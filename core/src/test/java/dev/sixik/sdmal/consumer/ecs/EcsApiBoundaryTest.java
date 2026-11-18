package dev.sixik.sdmal.consumer.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.EcsScheduler;
import dev.sixik.sdmal.api.ecs.EcsContext;
import dev.sixik.sdmal.api.ecs.EcsObject;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;
import dev.sixik.sdmal.api.ecs.annotations.EcsNoTickSkip;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.annotations.EcsUseAdapter;
import dev.sixik.sdmal.api.ecs.annotations.EcsUseNetworkAdapter;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializers;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Verifies the supported extension boundary from a package outside the ECS implementation.
 */
class EcsApiBoundaryTest {
    /**
     * All discovery, serialization and scheduling annotations share one import package.
     */
    @Test
    void annotations_haveOnePublicPackage() {
        Class<?>[] annotations = {
            EcsComponent.class,
            EcsData.class,
            EcsNoThreadSafe.class,
            EcsNoTickSkip.class,
            EcsPersist.class,
            EcsSync.class,
            EcsUseAdapter.class,
            EcsUseNetworkAdapter.class
        };
        for (Class<?> annotation : annotations) {
            assertTrue(annotation.isAnnotation());
            assertEquals("dev.sixik.sdmal.api.ecs.annotations", annotation.getPackageName());
        }
    }

    /**
     * Internal schema objects and concrete codec implementations cannot become consumer extension
     * points.
     *
     * @param name binary class name to inspect without initializing Minecraft classes
     * @throws ClassNotFoundException if a required implementation class is missing
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "dev.sixik.sdmal.api.ecs.ComponentTickSystem",
                "dev.sixik.sdmal.api.ecs.EcsExecutionPolicy",
                "dev.sixik.sdmal.api.ecs.EcsFailures",
                "dev.sixik.sdmal.api.ecs.EcsObjectArchive",
                "dev.sixik.sdmal.core.ecs.serialization.EcsComponentSchema",
                "dev.sixik.sdmal.core.ecs.serialization.EcsSchemaBuilder",
                "dev.sixik.sdmal.core.ecs.serialization.EcsNetworkSchema",
                "dev.sixik.sdmal.core.ecs.serialization.EcsNetworkSchemaBuilder",
                "dev.sixik.sdmal.api.ecs.sync.FieldCodecs$IntCodec",
                "dev.sixik.sdmal.api.ecs.codecs.ExternFieldCodecs$BlockPosCodec"
            })
    void implementationClasses_areNotPublic(String name) throws ClassNotFoundException {
        Class<?> type = Class.forName(name, false, getClass().getClassLoader());
        assertFalse(Modifier.isPublic(type.getModifiers()), name);
    }

    /**
     * Lifetime management and raw component serialization remain owned by their enclosing APIs.
     */
    @Test
    void publicHandles_doNotExposeInternalLifecycleAndSchemaOperations() {
        assertThrows(NoSuchMethodException.class, () -> EcsContext.class.getMethod("close"));
        assertThrows(NoSuchMethodException.class, () -> EcsScheduler.class.getMethod("close"));
        for (Class<?> registry :
                new Class<?>[] {EcsSerializers.class, EcsNetworkSerializers.class}) {
            for (var method : registry.getMethods()) {
                assertFalse(
                        Set.of(
                                        "writeComponent",
                                        "readComponent",
                                        "validateComponent",
                                        "schemaFingerprint")
                                .contains(method.getName()),
                        method.toString());
            }
        }
    }

    /**
     * Public signatures must use supported API types, including nested generic arguments and
     * bounds.
     *
     * @throws Exception if compiled API classes cannot be enumerated or resolved
     */
    @Test
    void publicSignatures_doNotLeakCoreOrInaccessibleTypes() throws Exception {
        Path classes =
                Path.of(
                        EcsObject.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path api = classes.resolve("dev/sixik/sdmal/api/ecs");
        try (var paths = Files.walk(api)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".class")).toList()) {
                String name =
                        classes.relativize(path).toString().replace('\\', '.').replace('/', '.');
                Class<?> type =
                        Class.forName(
                                name.substring(0, name.length() - 6),
                                false,
                                getClass().getClassLoader());
                if (!isAccessible(type)) {
                    continue;
                }
                Set<Type> checked = new HashSet<>();
                checkType(type.getGenericSuperclass(), checked);
                for (Type contract : type.getGenericInterfaces()) {
                    checkType(contract, checked);
                }
                for (var method : type.getDeclaredMethods()) {
                    if (!isApiMember(method.getModifiers())) {
                        continue;
                    }
                    checkType(method.getGenericReturnType(), checked);
                    for (Type parameter : method.getGenericParameterTypes()) {
                        checkType(parameter, checked);
                    }
                    for (Type exception : method.getGenericExceptionTypes()) {
                        checkType(exception, checked);
                    }
                }
                for (var constructor : type.getDeclaredConstructors()) {
                    if (!isApiMember(constructor.getModifiers())) {
                        continue;
                    }
                    for (Type parameter : constructor.getGenericParameterTypes()) {
                        checkType(parameter, checked);
                    }
                }
                for (var field : type.getDeclaredFields()) {
                    if (isApiMember(field.getModifiers())) {
                        checkType(field.getGenericType(), checked);
                    }
                }
            }
        }
    }

    /**
     * @param modifiers Java member modifiers
     * @return whether a consumer can call or inherit this member
     */
    private static boolean isApiMember(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /**
     * @param type class to inspect, including enclosing classes
     * @return whether the class is publicly accessible from an unrelated package
     */
    private static boolean isAccessible(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        return type.getDeclaringClass() == null || isAccessible(type.getDeclaringClass());
    }

    /**
     * Follows reflective type graphs without revisiting recursive generic bounds.
     *
     * @param type signature type, possibly null for Object's superclass
     * @param checked types already inspected in the current declaring class
     */
    private static void checkType(Type type, Set<Type> checked) {
        if (type == null || !checked.add(type)) {
            return;
        }
        if (type instanceof Class<?> concrete) {
            if (concrete.isArray()) {
                checkType(concrete.getComponentType(), checked);
                return;
            }
            if (concrete.getName().startsWith("dev.sixik.sdmal.")) {
                assertTrue(
                        concrete.getName().startsWith("dev.sixik.sdmal.api.ecs."),
                        concrete.getName());
                assertTrue(isAccessible(concrete), concrete.getName());
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            checkType(parameterized.getRawType(), checked);
            for (Type argument : parameterized.getActualTypeArguments()) {
                checkType(argument, checked);
            }
            return;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                checkType(bound, checked);
            }
            return;
        }
        if (type instanceof GenericArrayType array) {
            checkType(array.getGenericComponentType(), checked);
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                checkType(bound, checked);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                checkType(bound, checked);
            }
        }
    }
}
