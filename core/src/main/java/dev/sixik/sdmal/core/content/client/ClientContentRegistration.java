package dev.sixik.sdmal.core.content.client;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import dev.sixik.sdmal.core.content.ContentRegistration;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.ApiStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Client-only screen and renderer factories. This class is referenced exclusively by client loader
 * entry points; no common registration class references Minecraft client types.
 */
@ApiStatus.Internal
public final class ClientContentRegistration {
    /**
     * Common types already bound by the native registry lifecycle.
     */
    private final ContentRegistration common;
    /**
     * Screen constructors by matching common menu id, in deterministic order.
     */
    private final Map<String, MethodHandle> screens = new Object2ObjectAVLTreeMap<>();
    /**
     * Renderer constructors by matching common world entity id.
     */
    private final Map<String, MethodHandle> renderers = new Object2ObjectAVLTreeMap<>();
    /**
     * Standalone screen constructors; these have no common MenuType counterpart.
     */
    private final Map<String, MethodHandle> standaloneScreens = new Object2ObjectAVLTreeMap<>();
    /**
     * Client class-to-id lookups without generated Java references.
     */
    private final Map<Class<?>, String> ids = new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<>();
    /**
     * Whether screen factories have already been submitted to a loader.
     */
    private boolean screensEnlisted;
    /**
     * Whether renderer factories have already been submitted to a loader.
     */
    private boolean renderersEnlisted;

    /**
     * Validates every client factory before publishing any binding.
     *
     * @param common common annotated menu/entity registration
     * @param types classes loaded exclusively from the client index
     */
    public ClientContentRegistration(ContentRegistration common, Collection<Class<?>> types) {
        this.common = java.util.Objects.requireNonNull(common, "common");
        for (var type : types) {
            var annotation = type.getDeclaredAnnotation(RegisterContent.class);
            if (annotation == null || !annotation.id().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || annotation.id().length() > 256 || !annotation.tab().isEmpty()
                    || !Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())
                    || type.getTypeParameters().length != 0
                    || (type.isMemberClass() && !Modifier.isStatic(type.getModifiers()))) {
                throw new IllegalArgumentException("Invalid client @RegisterContent declaration: " + type.getName());
            }
            String id = annotation.id();
            try {
                Map<String, MethodHandle> destination;
                MethodHandle constructor;
                if (AbstractContainerScreen.class.isAssignableFrom(type)) {
                    Class<?> menu = common.menuClass(id);
                    ClientContentTypes.requireCompatible(type, AbstractContainerScreen.class, menu);
                    constructor = screenConstructor(type, menu);
                    destination = screens;
                } else if (EntityRenderer.class.isAssignableFrom(type)) {
                    ClientContentTypes.requireCompatible(type, EntityRenderer.class, common.entityClass(id));
                    constructor = MethodHandles.publicLookup().findConstructor(type,
                            MethodType.methodType(void.class, EntityRendererProvider.Context.class))
                            .asType(MethodType.methodType(EntityRenderer.class, EntityRendererProvider.Context.class));
                    destination = renderers;
                } else if (Screen.class.isAssignableFrom(type)) {
                    try {
                        constructor = MethodHandles.publicLookup().unreflectConstructor(type.getConstructor(Component.class))
                                .asType(MethodType.methodType(Screen.class, Component.class));
                    } catch (NoSuchMethodException noTitle) {
                        constructor = MethodHandles.dropArguments(MethodHandles.publicLookup().unreflectConstructor(
                                type.getConstructor()).asType(MethodType.methodType(Screen.class)),
                                0, Component.class);
                    }
                    destination = standaloneScreens;
                } else {
                    throw new IllegalArgumentException("Client content must extend Screen or EntityRenderer: " + type.getName());
                }
                if ((destination == screens && standaloneScreens.containsKey(id))
                        || (destination == standaloneScreens && screens.containsKey(id))) {
                    throw new IllegalArgumentException("Duplicate screen id across standalone and menu screens: " + id);
                }
                if (destination.putIfAbsent(id, constructor) != null) {
                    throw new IllegalArgumentException("Duplicate client content binding: " + id);
                }
                ids.put(type, id);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("Unsupported client content constructor: " + type.getName(), failure);
            }
        }
    }

    /**
     * Constructs a standalone screen; menu screens must be opened by the server's MenuProvider.
     *
     * @param id indexed standalone screen id
     * @param title caller-supplied title, ignored by a noarg constructor
     * @return newly constructed client screen
     */
    public Screen newScreen(String id, Component title) {
        MethodHandle constructor = standaloneScreens.get(id);
        if (constructor == null) {
            throw new IllegalArgumentException("Unknown standalone screen id: " + id);
        }
        java.util.Objects.requireNonNull(title, "title");
        try {
            return (Screen) constructor.invokeExact(title);
        } catch (Throwable cause) {
            throw failure(id, cause);
        }
    }

    /**
     * @param type indexed client declaration
     * @return stable annotation id
     */
    public String id(Class<?> type) {
        String id = ids.get(type);
        if (id == null) {
            throw new IllegalArgumentException("Unknown indexed client content: " + type.getName());
        }
        return id;
    }

    /**
     * Selects the most specific public menu/inventory/title constructor accepting the common menu.
     *
     * @param type screen implementation
     * @param menu registered menu implementation
     * @return cached generic menu factory handle
     * @throws ReflectiveOperationException if no supported constructor exists
     */
    private static MethodHandle screenConstructor(Class<?> type, Class<?> menu) throws ReflectiveOperationException {
        java.lang.reflect.Constructor<?> selected = null;
        for (var constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 3 || !AbstractContainerMenu.class.isAssignableFrom(parameters[0])
                    || !parameters[0].isAssignableFrom(menu)
                    || parameters[1] != Inventory.class || parameters[2] != Component.class) {
                continue;
            }
            if (selected == null || selected.getParameterTypes()[0].isAssignableFrom(parameters[0])) {
                selected = constructor;
            }
        }
        if (selected == null) {
            throw new NoSuchMethodException(type.getName() + "(matching menu, Inventory, Component)");
        }
        return MethodHandles.publicLookup().unreflectConstructor(selected)
                .asType(MethodType.methodType(AbstractContainerScreen.class,
                        AbstractContainerMenu.class, Inventory.class, Component.class));
    }

    /**
     * Menu screen factory independent of each loader's lifecycle callback signature.
     */
    @FunctionalInterface
    public interface ScreenFactory {
        /**
         * @param menu matching native menu instance
         * @param inventory local player inventory
         * @param title server-supplied menu title
         * @return newly constructed container screen
         */
        AbstractContainerScreen<AbstractContainerMenu> create(AbstractContainerMenu menu, Inventory inventory, Component title);
    }

    /**
     * Binds menu screens on the loader's client main-thread registration phase.
     *
     * @param output native MenuScreens/event registration bridge
     */
    @SuppressWarnings("unchecked")
    public void enlistScreens(BiConsumer<MenuType<?>, ScreenFactory> output) {
        if (screensEnlisted) {
            throw new IllegalStateException("Automatic screens are already registered");
        }
        screensEnlisted = true;
        for (var entry : screens.entrySet()) {
            MethodHandle constructor = entry.getValue();
            String id = entry.getKey();
            output.accept(common.menuType(id), (menu, inventory, title) -> {
                try {
                    return (AbstractContainerScreen<AbstractContainerMenu>) constructor.invokeExact(menu, inventory, title);
                } catch (Throwable failure) {
                    throw failure(id, failure);
                }
            });
        }
    }

    /**
     * Binds entity renderers during the loader's renderer registration event.
     *
     * @param output native renderer registration bridge
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void enlistRenderers(BiConsumer<EntityType<?>, EntityRendererProvider<?>> output) {
        if (renderersEnlisted) {
            throw new IllegalStateException("Automatic renderers are already registered");
        }
        renderersEnlisted = true;
        for (var entry : renderers.entrySet()) {
            MethodHandle constructor = entry.getValue();
            String id = entry.getKey();
            EntityRendererProvider provider = context -> {
                try {
                    return (EntityRenderer) constructor.invokeExact(context);
                } catch (Throwable failure) {
                    throw failure(id, failure);
                }
            };
            output.accept(common.entityType(id), provider);
        }
    }

    /**
     * Preserves fatal runtime failures while adding the failing content identity to user errors.
     *
     * @param id screen or renderer id
     * @param cause constructor failure
     * @return contextual client factory error
     */
    private static RuntimeException failure(String id, Throwable cause) {
        if (cause instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (cause instanceof LinkageError fatal) {
            throw fatal;
        }
        return new IllegalStateException("Cannot construct automatic client content " + id, cause);
    }
}
