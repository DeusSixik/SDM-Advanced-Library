package dev.sixik.sdmal.core.content.client;

import dev.sixik.sdmal.core.content.ContentRuntime;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.ApiStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads client-only indices when the physical client lifecycle requests screens or renderers.
 */
@ApiStatus.Internal
public final class ClientContentRuntime {
    /**
     * Compiler resource intentionally never read by common/server discovery.
     */
    public static final String INDEX = "META-INF/sdmal/content-client";
    /**
     * Shared client plan consumed by the separate screen and renderer lifecycle events.
     */
    private static ClientContentRegistration registration;

    /**
     * Prevents construction.
     */
    private ClientContentRuntime() {}

    /**
     * Loads and validates client declarations without initializing user static fields.
     *
     * @param loader mod classloader
     * @return validated client registration
     */
    public static ClientContentRegistration discover(ClassLoader loader) {
        var types = new ObjectLinkedOpenHashSet<Class<?>>();
        try {
            var resources = loader.getResources(INDEX);
            while (resources.hasMoreElements()) {
                try (var reader = new BufferedReader(new InputStreamReader(
                        resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    String name;
                    while ((name = reader.readLine()) != null) {
                        name = name.strip();
                        if (name.isEmpty() || name.startsWith("#")) {
                            continue;
                        }
                        types.add(Class.forName(name, false, loader));
                    }
                }
            }
        } catch (IOException | ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read automatic client content index", failure);
        }
        return new ClientContentRegistration(ContentRuntime.registration(), types);
    }

    /**
     * @return one validated plan for this physical client
     */
    public static synchronized ClientContentRegistration registration() {
        if (registration == null) {
            registration = discover(ClientContentRuntime.class.getClassLoader());
        }
        return registration;
    }
}
