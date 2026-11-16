package dev.sixik.sdmal;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
//? if >=1.21 {
/*import net.minecraftforge.fml.common.EventBusSubscriber;
 *///?} else {
import net.minecraftforge.fml.common.Mod;
//?}

/**
 * Receives client setup events without loading client classes on a dedicated server.
 */
//? if >=1.21 {
/*@EventBusSubscriber(modid = SDMAdvancedLibrary.MODID, value = Dist.CLIENT
    //? if <1.21.9
    , bus = EventBusSubscriber.Bus.MOD
)
*///?} else {
@Mod.EventBusSubscriber(modid = SDMAdvancedLibrary.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
//?}
public final class NeoForgeClientEvents {
    /**
     * Prevents instantiation of the static subscriber.
     */
    private NeoForgeClientEvents() {
    }

    /**
     * Runs shared client initialization.
     *
     * @param event client setup lifecycle event
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ClientSetup.initialize();
    }
}
