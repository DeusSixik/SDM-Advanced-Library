package dev.sixik.sdmal.example.content.client;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import dev.sixik.sdmal.example.content.AnnotatedMenu;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Container screen automatically bound to the menu sharing its annotation id.
 * A vanilla screen subclass keeps this example independent of versioned GUI drawing methods.
 */
@RegisterContent(id = "sdmal:annotated_menu")
public final class AnnotatedMenuScreen extends ContainerScreen {
    /**
     * @param menu automatically created native menu
     * @param inventory local player inventory
     * @param title title received from the server's MenuProvider
     */
    public AnnotatedMenuScreen(AnnotatedMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
