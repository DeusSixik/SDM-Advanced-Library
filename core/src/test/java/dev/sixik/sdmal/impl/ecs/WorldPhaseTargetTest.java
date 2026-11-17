package dev.sixik.sdmal.impl.ecs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the real vanilla boundaries used to integrate PRE/NORMAL/POST on each supported game version. */
class WorldPhaseTargetTest {
    /**
     * The server has one block-entity dispatch boundary after its entity iteration.
     * @throws IOException if Minecraft bytecode cannot be read
     */
    @Test void serverTick_hasOneBlockEntityBoundary() throws IOException {
        var tick = read("net/minecraft/server/level/ServerLevel").methods.stream()
            .filter(method -> method.name.equals("tick")).findFirst().orElseThrow();
        List<String> boundaries = new ArrayList<>();
        for (var instruction : tick.instructions) if (instruction instanceof MethodInsnNode call
                && call.name.equals("tickBlockEntities")) boundaries.add(call.owner + "." + call.name + call.desc);
        assertEquals(List.of("net/minecraft/server/level/ServerLevel.tickBlockEntities()V"), boundaries);
    }

    /**
     * Client entity ticking precedes ClientLevel.tick in the same Minecraft tick.
     * @throws IOException if Minecraft bytecode cannot be read
     */
    @Test void clientTick_entityBoundaryPrecedesWorldTick() throws IOException {
        var tick = read("net/minecraft/client/Minecraft").methods.stream()
            .filter(method -> method.name.equals("tick")).findFirst().orElseThrow();
        List<String> boundaries = new ArrayList<>();
        for (var instruction : tick.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals("net/minecraft/client/multiplayer/ClientLevel")
                && (call.name.equals("tickEntities") || call.name.equals("tick") || call.name.equals("tickBlockEntities"))) boundaries.add(call.name);
        boolean modern = boundaries.contains("tickBlockEntities");
        assertEquals(modern ? List.of("tickEntities", "tickBlockEntities", "tick") : List.of("tickEntities", "tick"), boundaries);
        var entities = read("net/minecraft/client/multiplayer/ClientLevel").methods.stream()
            .filter(method -> method.name.equals("tickEntities")).findFirst().orElseThrow();
        List<String> blockBoundaries = new ArrayList<>();
        for (var instruction : entities.instructions) if (instruction instanceof MethodInsnNode call
                && call.name.equals("tickBlockEntities")) blockBoundaries.add(call.owner + "." + call.name + call.desc);
        assertEquals(modern ? List.of() : List.of("net/minecraft/client/multiplayer/ClientLevel.tickBlockEntities()V"), blockBoundaries);
        ClassNode hook = read("dev/sixik/sdmal/impl/mixin/client/ecs/"
            + (modern ? "Mixin$ECS$Minecraft$worldLifecycle" : "Mixin$ECS$ClientLevel$tick"));
        var handler = hook.methods.stream().filter(method -> method.name.equals("sdm$normalTick")).findFirst().orElseThrow();
        var inject = handler.visibleAnnotations.stream().filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of(modern ? "tick" : "tickEntities"), value(inject, "method"));
        var at = (org.objectweb.asm.tree.AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V", value(at, "target"));
    }

    /**
     * Reads a declared annotation attribute for precise hook target verification.
     * @param annotation ASM annotation
     * @param name attribute name
     * @return declared value
     */
    private static Object value(org.objectweb.asm.tree.AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return fail("Missing annotation attribute " + name);
    }

    /**
     * Reads class structures without initializing client classes.
     * @param name JVM internal class name
     * @return parsed vanilla class
     * @throws IOException if the class cannot be found or read
     */
    private static ClassNode read(String name) throws IOException {
        try (var input = WorldPhaseTargetTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
