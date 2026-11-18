package dev.sixik.sdmal.content;

import dev.sixik.sdmal.core.content.client.ClientContentRuntime;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
//? if >=1.21 {
/*import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
*///?} else {
import net.minecraftforge.client.event.EntityRenderersEvent;
//?}

/**
 * Client-only NeoForge lifecycle bridges, including the legacy Forge-package 1.20.1 loader.
 */
public final class FmlClientContent {
    /**
     * Prevents construction.
     */
    private FmlClientContent() {}

    /**
     * Registers indexed screens in the appropriate native client lifecycle phase.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    //? if >=1.21 {
    /*public static void screens(RegisterMenuScreensEvent event) {
        ClientContentRuntime.registration().enlistScreens((type, factory) -> event.<AbstractContainerMenu, AbstractContainerScreen<AbstractContainerMenu>>register((MenuType<AbstractContainerMenu>) type,
                factory::create));
    }
    *///?} else {
    public static void screens() {
        ClientContentRuntime.registration().enlistScreens((type, factory) -> MenuScreens.<AbstractContainerMenu, AbstractContainerScreen<AbstractContainerMenu>>register((MenuType<AbstractContainerMenu>) type,
                factory::create));
    }
    //?}

    /**
     * Registers entity renderer providers without constructing them before their resource context exists.
     *
     * @param event client renderer lifecycle
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        ClientContentRuntime.registration().enlistRenderers((type, factory) ->
                event.registerEntityRenderer((EntityType) type, (EntityRendererProvider) factory));
    }
}
