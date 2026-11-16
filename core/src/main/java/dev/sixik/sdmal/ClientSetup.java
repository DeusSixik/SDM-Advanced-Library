package dev.sixik.sdmal;

import net.minecraft.client.Minecraft;

/** Client-only setup invoked exclusively by client entry points or client lifecycle events. */
public final class ClientSetup {
    /** Prevents instantiation of the utility class. */
    private ClientSetup() {}

    /** Logs the original initialization messages and current development player name. */
    public static void initialize() {
        SDMAdvancedLibrary.LOGGER.info("HELLO FROM CLIENT SETUP");
        SDMAdvancedLibrary.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
