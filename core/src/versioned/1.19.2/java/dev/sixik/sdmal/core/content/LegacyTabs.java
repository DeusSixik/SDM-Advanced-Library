package dev.sixik.sdmal.core.content;

import net.minecraft.world.item.CreativeModeTab;

import org.jetbrains.annotations.ApiStatus;

/**
 * Native 1.19.2 creative ids before creative tabs became a Minecraft registry.
 */
@ApiStatus.Internal
public final class LegacyTabs {
    /**
     * Prevents utility construction.
     */
    private LegacyTabs() {}

    /**
     * @param id namespaced tab identifier
     * @return native vanilla tab or null for a mod tab
     */
    public static CreativeModeTab find(String id) {
        return switch (id) {
            case "minecraft:building_blocks" -> CreativeModeTab.TAB_BUILDING_BLOCKS;
            case "minecraft:decorations" -> CreativeModeTab.TAB_DECORATIONS;
            case "minecraft:redstone" -> CreativeModeTab.TAB_REDSTONE;
            case "minecraft:transportation" -> CreativeModeTab.TAB_TRANSPORTATION;
            case "minecraft:misc" -> CreativeModeTab.TAB_MISC;
            case "minecraft:food" -> CreativeModeTab.TAB_FOOD;
            case "minecraft:tools" -> CreativeModeTab.TAB_TOOLS;
            case "minecraft:combat" -> CreativeModeTab.TAB_COMBAT;
            case "minecraft:brewing" -> CreativeModeTab.TAB_BREWING;
            case "minecraft:materials" -> CreativeModeTab.TAB_MATERIALS;
            case "minecraft:search" -> CreativeModeTab.TAB_SEARCH;
            case "minecraft:inventory" -> CreativeModeTab.TAB_INVENTORY;
            default -> null;
        };
    }
}
