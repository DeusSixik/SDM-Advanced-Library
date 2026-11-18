package dev.sixik.sdmal.content;

import dev.sixik.sdmal.core.content.client.ClientContentRuntime;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/**
 * Physical-client automatic screen and renderer bindings for Fabric.
 */
public final class FabricClientContent {
    /**
     * Prevents construction.
     */
    private FabricClientContent() {}

    /**
     * Registers client factories after Fabric's common content initialization has completed.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void initialize() {
        var registration = ClientContentRuntime.registration();
        registration.enlistScreens((type, factory) -> MenuScreens.<AbstractContainerMenu, AbstractContainerScreen<AbstractContainerMenu>>register((MenuType<AbstractContainerMenu>) type,
                factory::create));
        registration.enlistRenderers((type, factory) -> EntityRendererRegistry.register((net.minecraft.world.entity.EntityType) type,
                (net.minecraft.client.renderer.entity.EntityRendererProvider) factory));
    }
}
