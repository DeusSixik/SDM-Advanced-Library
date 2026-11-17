package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.api.content.ContentTab;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Native registry category inferred from a declared Java type.
 */
enum ContentKind {
    /**
     * Item or explicit block item.
     */
    ITEM,
    /**
     * Explicit block implementation.
     */
    BLOCK,
    /**
     * Native block entity implementation.
     */
    BLOCK_ENTITY,
    /**
     * Version-independent creative tab customization.
     */
    TAB;

    /**
     * @param type annotated Java class
     * @return inferred registry kind
     * @throws IllegalArgumentException if the class has no supported base type
     */
    static ContentKind of(Class<?> type) {
        if (Item.class.isAssignableFrom(type)) return ITEM;
        if (Block.class.isAssignableFrom(type)) return BLOCK;
        if (BlockEntity.class.isAssignableFrom(type)) return BLOCK_ENTITY;
        if (ContentTab.class.isAssignableFrom(type)) return TAB;
        throw new IllegalArgumentException(
                "@RegisterContent requires Item, Block, BlockEntity or ContentTab: "
                        + type.getName());
    }
}
