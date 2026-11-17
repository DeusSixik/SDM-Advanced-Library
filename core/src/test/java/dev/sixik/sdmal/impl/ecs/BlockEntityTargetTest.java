package dev.sixik.sdmal.impl.ecs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Checks actual Minecraft lifecycle/network signatures on every supported game version.
 */
class BlockEntityTargetTest {
    /**
     * Common chunk teardown has one concrete target and a matching shadow accessor.
     *
     * @throws IOException if class resources are unavailable
     */
    @Test
    void chunkUnloadHook_currentBytecode_hasCompatibleTarget() throws IOException {
        var chunk = read("net/minecraft/world/level/chunk/LevelChunk");
        var cleanup =
                chunk.methods.stream().filter(m -> m.name.equals("clearAllBlockEntities")).toList();
        assertEquals(1, cleanup.size());
        assertEquals("()V", cleanup.get(0).desc);
        assertEquals(0, cleanup.get(0).access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT));
        var mixin = read("dev/sixik/sdmal/impl/mixin/ecs/Mixin$ECS$LevelChunk$blockEntities");
        var target =
                mixin.invisibleAnnotations.stream()
                        .filter(a -> a.desc.endsWith("/Mixin;"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of(Type.getObjectType(chunk.name)), value(target, "value"));
        var handler =
                mixin.methods.stream()
                        .filter(m -> m.name.equals("sdm$releaseHostedObjects"))
                        .findFirst()
                        .orElseThrow();
        var inject =
                handler.visibleAnnotations.stream()
                        .filter(a -> a.desc.endsWith("/Inject;"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of("clearAllBlockEntities"), value(inject, "method"));
        var at = (AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("HEAD", value(at, "value"));
        var shadow =
                mixin.methods.stream()
                        .filter(m -> m.name.equals("getBlockEntities"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                chunk.methods.stream()
                        .anyMatch(m -> m.name.equals(shadow.name) && m.desc.equals(shadow.desc)));
    }

    /**
     * Version-selected NBT hooks, world lifecycle hooks and packet hooks override real vanilla
     * methods.
     *
     * @throws IOException if class resources are unavailable
     */
    @Test
    void shellOverrides_currentBytecode_matchVanillaSignatures() throws IOException {
        var vanilla = read("net/minecraft/world/level/block/entity/BlockEntity");
        var shell = read("dev/sixik/sdmal/api/ecs/minecraft/blockentity/EcsBlockEntity");
        var names =
                Set.of(
                        "load",
                        "loadAdditional",
                        "saveAdditional",
                        "getUpdateTag",
                        "getUpdatePacket",
                        "setLevel",
                        "setRemoved",
                        "clearRemoved");
        int overrides = 0;
        for (var method : shell.methods) {
            if (!names.contains(method.name)) continue;
            overrides++;
            assertTrue(
                    vanilla.methods.stream()
                            .anyMatch(
                                    m ->
                                            m.name.equals(method.name)
                                                    && m.desc.equals(method.desc)
                                                    && (m.access
                                                                    & (Opcodes.ACC_FINAL
                                                                            | Opcodes.ACC_STATIC))
                                                            == 0),
                    method.name + method.desc);
        }
        assertEquals(7, overrides);
    }

    /**
     * Requests use vanilla packet creation, whose implementation dispatches the selected update-tag
     * override.
     *
     * @throws IOException if class resources are unavailable
     */
    @Test
    void updatePacket_creation_usesVanillaTagDispatch() throws IOException {
        var shell = read("dev/sixik/sdmal/api/ecs/minecraft/blockentity/EcsBlockEntity");
        var method =
                shell.methods.stream()
                        .filter(m -> m.name.equals("getUpdatePacket"))
                        .findFirst()
                        .orElseThrow();
        int creates = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("create")) {
                assertEquals(
                        "net/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket",
                        call.owner);
                var packet = read(call.owner);
                assertTrue(
                        packet.methods.stream()
                                .anyMatch(
                                        m -> m.name.equals(call.name) && m.desc.equals(call.desc)));
                creates++;
            }
        }
        assertEquals(1, creates);
    }

    /**
     * Reads an explicitly declared annotation attribute.
     *
     * @param annotation parsed annotation
     * @param name attribute name
     * @return attribute value
     */
    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return fail("Missing annotation attribute " + name);
    }

    /**
     * Reads bytecode without initializing Minecraft classes.
     *
     * @param name JVM internal class name
     * @return parsed class
     * @throws IOException if class resources are unavailable
     */
    private static ClassNode read(String name) throws IOException {
        try (var input =
                BlockEntityTargetTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var result = new ClassNode();
            new ClassReader(input).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result;
        }
    }
}
