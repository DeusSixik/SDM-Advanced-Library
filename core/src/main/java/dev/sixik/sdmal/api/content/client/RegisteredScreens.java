package dev.sixik.sdmal.api.content.client;

import dev.sixik.sdmal.core.content.client.ClientContentRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Physical-client API for standalone Screen factories discovered through RegisterContent.
 * Container screens are opened through their server MenuProvider and must not be opened here.
 * Never reference this class from common/server static initializers.
 */
public final class RegisteredScreens {
    /**
     * Prevents construction.
     */
    private RegisteredScreens() {}

    /**
     * Constructs a registered standalone screen on the Minecraft client thread.
     *
     * @param type annotated standalone screen implementation
     * @param title screen title; noarg constructors supply their own title
     * @param <S> concrete screen class
     * @return newly constructed screen
     * @throws IllegalStateException when called outside the client thread
     */
    public static <S extends Screen> S create(Class<S> type, Component title) {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Construct registered screens on the Minecraft client thread");
        }
        var registration = ClientContentRuntime.registration();
        return type.cast(registration.newScreen(registration.id(type), title));
    }

    /**
     * Queues standalone screen creation and display on the client thread.
     *
     * @param type annotated standalone screen implementation
     * @param title non-null screen title
     * @param <S> concrete screen class
     */
    public static <S extends Screen> void open(Class<S> type, Component title) {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(title, "title");
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            //? if >=26.3 {
            /*client.setScreenAndShow(create(type, title));
            *///?} else {
            client.setScreen(create(type, title));
            //?}
        });
    }
}
