package dev.sixik.sdmal.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.IOException;

/** Prepares the version-selected sources and separate core/impl Mixin configurations. */
@CacheableTask
public abstract class PrepareMixinSources extends DefaultTask {
    /**
     * Returns the core sources after Stonecutter preprocessing and directory selection.
     * @return non-null, tracked collection of Java files
     */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCoreSources();

    /**
     * Returns implementation and loader sources after Stonecutter preprocessing.
     * @return non-null, tracked collection of Java files
     */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getImplSources();

    /**
     * Returns the target Minecraft version.
     * @return numeric version property
     */
    @Input public abstract Property<String> getMinecraftVersion();

    /**
     * Returns the Mixin package root, shared by the core and impl subpackages.
     * @return Java package property such as dev.sixik.sdmal
     */
    @Input public abstract Property<String> getPackageRoot();

    /**
     * Returns the prefix of both generated JSON file names.
     * @return non-empty mod identifier property
     */
    @Input public abstract Property<String> getModId();

    /**
     * Returns the target Java level used by the Mixin compatibility setting.
     * @return positive Java language version property
     */
    @Input public abstract Property<Integer> getJavaVersion();

    /**
     * Returns the optional refmap name shared by both configs on obfuscated Forge targets.
     * @return file name property; an empty value omits the refmap key
     */
    @Input public abstract Property<String> getRefmapName();

    /** Initializes optional metadata defaults for all loader variants. */
    public PrepareMixinSources() {
        getRefmapName().convention("");
    }

    /**
     * Returns the generated output root, containing java and resources directories.
     * @return build directory owned exclusively by this task
     */
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    /**
     * Provides Gradle's service for removing stale generated output.
     * @return injected file operation service
     */
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    /**
     * Removes stale outputs and generates sources and both configurations deterministically.
     * @throws IOException if source files cannot be read or generated output cannot be written
     * @throws IllegalArgumentException if source syntax or mixin packages are invalid or variants overlap
     */
    @TaskAction
    public void generate() throws IOException {
        var output = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        MixinSourceGenerator.generate(
            getCoreSources().getFiles().stream().map(java.io.File::toPath).toList(),
            getImplSources().getFiles().stream().map(java.io.File::toPath).toList(),
            getMinecraftVersion().get(), getPackageRoot().get(), getModId().get(),
            getJavaVersion().get(), getRefmapName().get(), output);
    }
}
