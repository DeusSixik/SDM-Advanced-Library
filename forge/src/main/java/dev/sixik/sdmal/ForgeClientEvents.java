package dev.sixik.sdmal;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
//? if >=26.1 {
/*import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
 *///?} else {
import net.minecraftforge.eventbus.api.SubscribeEvent;
//?}

/**
 * Receives client lifecycle events without loading client classes on a dedicated server.
 */
@Mod.EventBusSubscriber(modid = SDMAdvancedLibrary.MODID, value = Dist.CLIENT
        //? if <26.1
        , bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class ForgeClientEvents {
    /**
     * Prevents instantiation of the static subscriber.
     */
    private ForgeClientEvents() {
    }

    /**
     * Runs shared client initialization.
     *
     * @param event client setup lifecycle event
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(dev.sixik.sdmal.content.FmlClientContent::screens);
        ClientSetup.initialize();
    }

    /**
     * Installs indexed world entity renderers during the native renderer lifecycle.
     *
     * @param event client renderer registration event
     */
    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        dev.sixik.sdmal.content.FmlClientContent.renderers(event);
    }
}
