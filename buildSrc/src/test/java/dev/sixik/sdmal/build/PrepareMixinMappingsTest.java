package dev.sixik.sdmal.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies compressed Forge mappings preserve method descriptors and remapping direction. */
class PrepareMixinMappingsTest {
    /** Isolated mapping directory supplied by JUnit. */
    @TempDir Path directory;

    /**
     * Converts an actual TSRG2 shape into the exact SRG entry expected by Mixin.
     * @throws IOException if temporary mapping files cannot be read or written
     */
    @Test void convert_compressedTsrg2_preservesOfficialToSrgMethod() throws IOException {
        Path compressed = directory.resolve("input.tsrg.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
            output.write(("tsrg2 left right\n"
                + "net/minecraft/server/MinecraftServer net/minecraft/server/MinecraftServer\n"
                + "\tgetTickCount ()I m_129921_\n").getBytes(StandardCharsets.UTF_8));
        }
        Path normalized = directory.resolve("normalized/output.srg");
        Path runtime = directory.resolve("runtime/output.srg");
        PrepareMixinMappings.convert(compressed, normalized, runtime);
        assertTrue(Files.readString(normalized).contains("MD: net/minecraft/server/MinecraftServer/getTickCount ()I "
            + "net/minecraft/server/MinecraftServer/m_129921_ ()I"));
        assertTrue(Files.readString(runtime).contains("MD: net/minecraft/server/MinecraftServer/m_129921_ ()I "
            + "net/minecraft/server/MinecraftServer/getTickCount ()I"));
    }

    /**
     * Regresses the client startup failure: production SRG selectors must resolve to Mojmap in userdev.
     * @throws IOException if temporary mapping files cannot be read or written
     */
    @Test void convert_plainSrg_reversesClientLifecycleAndShadowFieldsForDevelopment() throws IOException {
        String minecraft = "net/minecraft/client/Minecraft/";
        String descriptor = "(Lnet/minecraft/client/multiplayer/ClientLevel;)V";
        String productionMethod = "MD: " + minecraft + "updateLevelInEngines " + descriptor
            + " " + minecraft + "m_91324_ " + descriptor;
        String runtimeMethod = "MD: " + minecraft + "m_91324_ " + descriptor
            + " " + minecraft + "updateLevelInEngines " + descriptor;
        String productionField = "FD: " + minecraft + "level " + minecraft + "f_91073_";
        String runtimeField = "FD: " + minecraft + "f_91073_ " + minecraft + "level";
        Path input = directory.resolve("input.srg");
        Files.writeString(input, "CL: net/minecraft/client/Minecraft net/minecraft/client/Minecraft\n"
            + productionMethod + "\n" + productionField + "\n");

        Path processor = directory.resolve("processor/output.srg");
        Path runtime = directory.resolve("runtime/output.srg");
        PrepareMixinMappings.convert(input, processor, runtime);

        String processorText = Files.readString(processor);
        String runtimeText = Files.readString(runtime);
        assertTrue(processorText.contains(productionMethod));
        assertTrue(processorText.contains(productionField));
        assertTrue(runtimeText.contains(runtimeMethod));
        assertTrue(runtimeText.contains(runtimeField));
        assertFalse(runtimeText.contains(productionMethod));
    }
}
