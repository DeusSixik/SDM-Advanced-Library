package dev.sixik.sdmal;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client-only initialization, kept out of dedicated server class loading.
 */
public final class FabricClientEntrypoint implements ClientModInitializer {
    /**
     * Public no-argument constructor used by Fabric Loader.
     */
    public FabricClientEntrypoint() {}

    /**
     * Logs the original client initialization message and development player name.
     */
    @Override
    public void onInitializeClient() {
        dev.sixik.sdmal.content.FabricClientContent.initialize();
        dev.sixik.sdmal.network.FabricClientNetwork.initialize();
        ClientSetup.initialize();
    }
}
