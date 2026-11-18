package dev.sixik.sdmal.content;

import dev.sixik.sdmal.core.content.client.ClientContentRuntime;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.client.event.EntityRenderersEvent;

/**
 * Client-only Forge lifecycle bridges for processor-indexed screens and entity renderers.
 */
public final class FmlClientContent {
    /**
     * Prevents construction.
     */
    private FmlClientContent() {}

    /**
     * Registers screens on the main thread after common menu registry events have completed.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void screens() {
        ClientContentRuntime.registration().enlistScreens((type, factory) -> MenuScreens.<AbstractContainerMenu, AbstractContainerScreen<AbstractContainerMenu>>register((MenuType<AbstractContainerMenu>) type,
                factory::create));
    }

    /**
     * Registers world entity renderer providers during the native renderer lifecycle.
     *
     * @param event physical-client renderer registration event
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        ClientContentRuntime.registration().enlistRenderers((type, factory) ->
                event.registerEntityRenderer((EntityType) type, (EntityRendererProvider) factory));
    }
}
