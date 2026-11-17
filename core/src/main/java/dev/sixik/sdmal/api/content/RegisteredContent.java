package dev.sixik.sdmal.api.content;

import dev.sixik.sdmal.core.content.ContentRuntime;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Resolves automatically registered content after its native registry event. No generated Java
 * references are needed. Use lazy lookups from gameplay methods; registry dependencies may not be
 * available inside another constructor.
 */
public final class RegisteredContent {
    /**
     * Prevents utility construction.
     */
    private RegisteredContent() {
    }

    /**
     * @param id automatic block id, including a generated block for a block entity
     * @return registered block
     * @throws IllegalStateException if its registration has not completed
     */
    public static Block block(String id) {
        return ContentRuntime.registration().block(id);
    }

    /**
     * @param type annotated block class
     * @param <T>  block type
     * @return registered instance of the exact class
     */
    public static <T extends Block> T block(Class<T> type) {
        return type.cast(block(ContentRuntime.registration().id(type)));
    }

    /**
     * @param id automatic item id, including a generated block item
     * @return registered item
     * @throws IllegalStateException if its registration has not completed
     */
    public static Item item(String id) {
        return ContentRuntime.registration().item(id);
    }

    /**
     * @param type annotated item class
     * @param <T>  item type
     * @return registered instance of the exact class
     */
    public static <T extends Item> T item(Class<T> type) {
        return type.cast(item(ContentRuntime.registration().id(type)));
    }

    /**
     * @param type annotated concrete block entity class
     * @param <T>  block entity type
     * @return its registered native factory type
     */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> BlockEntityType<T> blockEntityType(Class<T> type) {
        return (BlockEntityType<T>)
                ContentRuntime.registration()
                        .blockEntityType(ContentRuntime.registration().id(type));
    }

    /**
     * @param id creative tab id owned or referenced by annotated content
     * @return native tab after its registration
     */
    public static CreativeModeTab tab(String id) {
        return ContentRuntime.registration().tab(id);
    }
}
