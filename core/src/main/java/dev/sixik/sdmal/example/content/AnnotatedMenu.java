package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;

/**
 * One-row example container using an injected registered type on both physical sides.
 * This temporary inventory demonstrates registration; a persistent machine supplies its own storage.
 */
@RegisterContent(id = "sdmal:annotated_menu")
public final class AnnotatedMenu extends ChestMenu {
    /**
     * Creates the client/server inventory layout without manual MenuType fields.
     *
     * @param type automatically registered native menu type
     * @param windowId native container synchronization id
     * @param inventory opening player's inventory
     */
    public AnnotatedMenu(MenuType<?> type, int windowId, Inventory inventory) {
        super(type, windowId, inventory, new SimpleContainer(9), 1);
    }

    /**
     * Returns temporary example inventory contents to the player instead of losing them on close.
     *
     * @param player player closing the container
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        clearContainer(player, getContainer());
    }
}
