package dev.sixik.sdmal.build;

import net.minecraftforge.srgutils.IMappingFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Prepares legacy Forge mappings for Mixin annotation processing and development launches. */
@CacheableTask
public abstract class PrepareMixinMappings extends DefaultTask {
    /**
     * Returns ForgeGradle's mapping input; gzip-compressed files are supported.
     * @return tracked official-to-SRG mapping file
     */
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputMappings();

    /**
     * Returns the normalized output consumed by Mixin's annotation processor.
     * @return uncompressed SRG output file
     */
    @OutputFile public abstract RegularFileProperty getOutputMappings();

    /**
     * Returns the inverse mappings used to translate production refmaps in development.
     * @return uncompressed SRG-to-official mapping file
     */
    @OutputFile public abstract RegularFileProperty getRuntimeMappings();

    /**
     * Writes official-to-SRG processor mappings and SRG-to-official development mappings.
     * @throws IOException if mappings cannot be loaded or written
     */
    @TaskAction public void generate() throws IOException {
        convert(getInputMappings().get().getAsFile().toPath(), getOutputMappings().get().getAsFile().toPath(),
            getRuntimeMappings().get().getAsFile().toPath());
    }

    /**
     * Normalizes mapping syntax for legacy Forge Mixin processing.
     * @param input plain or gzip-compressed mappings in any srgutils-supported format
     * @param output destination for official-to-SRG processor mappings
     * @param runtimeOutput destination for inverse SRG-to-official development mappings
     * @throws IOException if input mappings are malformed or file operations fail
     */
    static void convert(Path input, Path output, Path runtimeOutput) throws IOException {
        Files.createDirectories(output.getParent());
        Files.createDirectories(runtimeOutput.getParent());
        try (var raw = Files.newInputStream(input);
             var stream = input.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            IMappingFile mappings = IMappingFile.load(stream);
            mappings.write(output, IMappingFile.Format.SRG, false);
            mappings.reverse().write(runtimeOutput, IMappingFile.Format.SRG, false);
        }
    }
}
