package dev.sixik.sdmal.api.ecs.minecraft.blockentity;

import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Resolves vanilla fixture types whose static registry holders moved in Minecraft 26.3.
 */
final class BlockEntityFixtures {
    /**
     * Prevents fixture utility construction.
     */
    private BlockEntityFixtures() {}

    /**
     * Resolves the actual registered furnace type without constructing a new registry entry.
     *
     * @return vanilla furnace type
     * @throws AssertionError if the Minecraft fixture registry changes
     */
    static BlockEntityType<?> furnaceType() {
        try {
            Class<?> holder;
            try {
                holder = Class.forName("net.minecraft.world.level.block.entity.BlockEntityTypes");
            } catch (ClassNotFoundException legacy) {
                holder = BlockEntityType.class;
            }
            return (BlockEntityType<?>) holder.getField("FURNACE").get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Minecraft furnace fixture is unavailable", failure);
        }
    }
}
