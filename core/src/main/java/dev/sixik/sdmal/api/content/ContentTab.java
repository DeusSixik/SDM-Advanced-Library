package dev.sixik.sdmal.api.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Version-independent creative tab customization, annotated with RegisterContent and a public noarg
 * constructor. Native tab construction is performed by the loader. Item lookups belong in
 * icon/appendItems, not the constructor.
 */
public interface ContentTab {
    /**
     * @return custom title, or null for the default itemGroup.namespace.path translation key
     */
    default Component title() {
        return null;
    }

    /**
     * @return custom icon, or EMPTY to use the first sorted automatic entry (a chest for an empty
     * tab)
     */
    default ItemStack icon() {
        return ItemStack.EMPTY;
    }

    /**
     * Adds optional extra entries after the ordered annotated content. Do not add the same stack
     * twice.
     *
     * @param output native tab output used during content rebuild, never retained by the
     *               implementation
     */
    default void appendItems(Consumer<ItemStack> output) {
    }
}
