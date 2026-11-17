package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.SDMAdvancedLibrary;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.ApiStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;

/**
 * Loader bridge for one process-wide automatic content registration.
 */
@ApiStatus.Internal
public final class ContentRuntime {
    /**
     * Resource-only processor index contributed by every consuming module.
     */
    public static final String INDEX = "META-INF/sdmal/content";

    /**
     * Installed registration; published before factories can consult RegisteredContent.
     */
    private static volatile ContentRegistration registration;

    /**
     * Prevents utility construction.
     */
    private ContentRuntime() {}

    /**
     * Discovers classes without initialization; compilation indices are sufficient, no generated
     * Java API is used.
     *
     * @param loader mod classloader
     * @return completely validated registration plan
     */
    public static ContentRegistration discover(ClassLoader loader) {
        var types = new LinkedHashSet<Class<?>>();
        try {
            var resources = loader.getResources(INDEX);
            while (resources.hasMoreElements()) {
                try (var reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        resources.nextElement().openStream(),
                                        StandardCharsets.UTF_8))) {
                    String name;
                    while ((name = reader.readLine()) != null) {
                        name = name.strip();
                        if (name.isEmpty() || name.startsWith("#")) continue;
                        types.add(Class.forName(name, false, loader));
                    }
                }
            }
            return new ContentRegistration(types);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("Cannot read automatic content index", failure);
        }
    }

    /**
     * @param backend loader native registration backend
     * @return installed registration
     */
    public static synchronized ContentRegistration install(ContentRegistration.Backend backend) {
        if (registration != null)
            throw new IllegalStateException("Content backend is already installed");
        registration = discover(ContentRuntime.class.getClassLoader());
        registration.enlist(backend);
        SDMAdvancedLibrary.LOGGER.info("Automatic annotated content enlisted");
        return registration;
    }

    /**
     * @return installed runtime or an explanatory lifecycle error
     */
    public static ContentRegistration registration() {
        var result = registration;
        if (result == null)
            throw new IllegalStateException("Automatic content has not initialized");
        return result;
    }

    /**
     * Reorders automatic items after the legacy vanilla tab builder has visited the item registry.
     *
     * @param tab rebuilt legacy tab
     * @param output native mutable stack list
     */
    public static void appendLegacy(CreativeModeTab tab, NonNullList<ItemStack> output) {
        var current = registration;
        if (current == null) return;
        var id = current.tabId(tab);
        if (id == null || current.ownsTab(id)) return;
        for (int index = output.size() - 1; index >= 0; index--)
            if (current.isAutomaticEntry(tab, output.get(index))) output.remove(index);
        current.appendTab(id, output::add);
    }

    /**
     * @param tab legacy native tab
     * @return owned tab title, or null for ordinary native tabs
     */
    public static net.minecraft.network.chat.Component legacyTitle(CreativeModeTab tab) {
        var current = registration;
        if (current == null) return null;
        var id = current.tabId(tab);
        return id == null || !current.ownsTab(id) ? null : current.title(id);
    }
}
