package dev.sixik.sdmal.example.item;

import dev.sixik.sdmal.example.ecs.multiblock.MultiblockControllerBlockEntity;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Existing example food item with a Shift-right-click shortcut for assembling the demo multiblock.
 * Item interaction handles sneaking because vanilla normally bypasses the block's click handler
 * then.
 */
public final class ExampleItem extends Item {
    /**
     * Creates the example item with the shared factory's food and registry properties.
     *
     * @param properties version-appropriate registered item properties
     */
    public ExampleItem(Properties properties) {
        super(properties);
    }

    /**
     * Requests assembly on the server when a sneaking player clicks a multiblock controller. The
     * item and building materials are not consumed by this demonstration shortcut.
     *
     * @param context actual clicked block, player and interaction hand
     * @return success for assembly interactions, otherwise the ordinary item interaction result
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return super.useOn(context);
        var level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos())
                instanceof MultiblockControllerBlockEntity controller)) {
            return super.useOn(context);
        }
        if (!level.isClientSide()) controller.assemble(player);
        return InteractionResult.SUCCESS;
    }
}
