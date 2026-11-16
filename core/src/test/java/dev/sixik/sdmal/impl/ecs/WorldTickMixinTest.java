package dev.sixik.sdmal.impl.ecs;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Checks tick injection targets against each version's actual Minecraft bytecode without loading client classes. */
class WorldTickMixinTest {

    /**
     * Both hooks target one concrete world tick and dispatch once at its normal completion.
     * @param mixin internal JVM name of the compiled tick mixin
     * @param target internal JVM name of the vanilla world implementation
     * @throws IOException if class resources cannot be read
     */
    @ParameterizedTest
    @CsvSource({
        "dev/sixik/sdmal/impl/mixin/ecs/Mixin$ECS$ServerLevel$tick, net/minecraft/server/level/ServerLevel",
        "dev/sixik/sdmal/impl/mixin/client/ecs/Mixin$ECS$ClientLevel$tick, net/minecraft/client/multiplayer/ClientLevel"
    })
    void tickHook_currentMinecraftBytecode_hasCompatibleTarget(String mixin, String target) throws IOException {
        ClassNode hook = readClass(mixin);
        AnnotationNode mixinAnnotation = hook.invisibleAnnotations.stream()
            .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
            .findFirst().orElseThrow();
        assertEquals(List.of(Type.getObjectType(target)), value(mixinAnnotation, "value"));
        var handler = hook.methods.stream().filter(method -> method.name.equals("sdm$tick")).findFirst().orElseThrow();
        assertEquals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", handler.desc);
        AnnotationNode inject = handler.visibleAnnotations.stream()
            .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
            .findFirst().orElseThrow();
        assertEquals(List.of("tick"), value(inject, "method"));
        assertEquals("TAIL", value((AnnotationNode) ((List<?>) value(inject, "at")).get(0), "value"));

        var targets = readClass(target).methods.stream().filter(method -> method.name.equals("tick")).toList();
        assertEquals(1, targets.size(), "A name-only injector must resolve to one vanilla method");
        var tick = targets.get(0);
        assertEquals("(Ljava/util/function/BooleanSupplier;)V", tick.desc);
        assertEquals(0, tick.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT));
        boolean returnsNormally = false;
        for (var instruction : tick.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) returnsNormally = true;
        }
        assertTrue(returnsNormally, "TAIL requires a normal return in the world tick");

        int dispatches = 0;
        int pauseChecks = 0;
        for (var instruction : handler.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("dev/sixik/sdmal/impl/ecs/WorldObjectTicker") && call.name.equals("tick")) dispatches++;
                if (call.name.equals("runsNormally")) pauseChecks++;
            }
        }
        assertEquals(1, dispatches);
        boolean supportsFreeze = readClass("net/minecraft/world/level/Level").methods.stream()
            .anyMatch(method -> method.name.equals("tickRateManager"));
        assertEquals(supportsFreeze ? 1 : 0, pauseChecks, "Pause support must follow this Minecraft version");
    }

    /**
     * World cleanup hooks resolve uniquely despite version-dependent disconnect arguments.
     * @param mixin internal JVM name of the lifecycle mixin
     * @param target internal JVM name of the vanilla world class
     * @param method vanilla method used to release the world
     * @param handler compiled cleanup callback
     * @throws IOException if class resources cannot be read
     */
    @ParameterizedTest
    @CsvSource({
        "dev/sixik/sdmal/impl/mixin/ecs/Mixin$ECS$Level$objectManager, net/minecraft/world/level/Level, close, sdm$close",
        "dev/sixik/sdmal/impl/mixin/client/ecs/Mixin$ECS$ClientLevel$tick, net/minecraft/client/multiplayer/ClientLevel, disconnect, sdm$disconnect"
    })
    void cleanupHook_currentMinecraftBytecode_unregistersManager(String mixin, String target,
                                                                 String method, String handler) throws IOException {
        var targets = readClass(target).methods.stream().filter(candidate -> candidate.name.equals(method)).toList();
        assertEquals(1, targets.size());
        assertTrue(targets.get(0).desc.endsWith(")V"));
        var callback = readClass(mixin).methods.stream().filter(candidate -> candidate.name.equals(handler))
            .findFirst().orElseThrow();
        assertEquals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", callback.desc);
        int unregisters = 0;
        for (var instruction : callback.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.owner.equals("dev/sixik/sdmal/core/ecs/minecraft/WorldObjectManagerApi")
                && call.name.equals("unregisterWorld")) unregisters++;
        }
        assertEquals(1, unregisters);
        assertTrue(callback.tryCatchBlocks.stream()
            .anyMatch(block -> "dev/sixik/sdmal/core/ecs/WorldObjectException".equals(block.type)));
    }

    /**
     * The client replacement/unload hook targets the implementation overload used in this Minecraft version.
     * @throws IOException if class resources cannot be read
     */
    @Test void worldReplacementHook_currentMinecraftBytecode_hasCompatibleDescriptor() throws IOException {
        ClassNode mixin = readClass("dev/sixik/sdmal/impl/mixin/client/ecs/Mixin$ECS$Minecraft$worldLifecycle");
        var handler = mixin.methods.stream().filter(method -> method.name.equals("sdm$replaceWorld"))
            .findFirst().orElseThrow();
        var inject = handler.visibleAnnotations.stream()
            .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
            .findFirst().orElseThrow();
        String selector = (String) ((List<?>) value(inject, "method")).get(0);
        ClassNode minecraft = readClass("net/minecraft/client/Minecraft");
        var targets = minecraft.methods.stream().filter(method -> (method.name + method.desc).equals(selector)).toList();
        assertEquals(1, targets.size());
        String expected = targets.get(0).desc.replace(")V", "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        assertEquals(expected, handler.desc);
        assertEquals("HEAD", value((AnnotationNode) ((List<?>) value(inject, "at")).get(0), "value"));
    }

    /**
     * Reads bytecode without resolving or initializing Minecraft classes.
     * @param name internal JVM class name, non-null
     * @return parsed class including annotations and instruction lists
     * @throws IOException if the resource cannot be read
     */
    private static ClassNode readClass(String name) throws IOException {
        try (var source = WorldTickMixinTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(source, name);
            ClassNode node = new ClassNode();
            new ClassReader(source).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    /**
     * Looks up a named ASM annotation value.
     * @param annotation parsed annotation, non-null
     * @param name requested attribute name, non-null
     * @return attribute value; fails the test if absent
     */
    private static Object value(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return fail("Missing annotation attribute " + name);
    }
}
