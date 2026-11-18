package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.world.item.Item;
import dev.sixik.sdmal.api.content.RegisteredContent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
//? if <1.21.2 {
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
//?}

/**
 * First entry in the automatic example tab; no manual loader registration is needed.
 */
@RegisterContent(id = "sdmal:annotated_token", tab = "sdmal:annotated_content", ordinal = -1)
public final class AnnotatedToken extends Item {
    /**
     * @param properties injected registry-key-aware item properties
     */
    public AnnotatedToken(Properties properties) {
        super(properties.stacksTo(16));
    }

    /**
     * Opens the automatically bound menu/screen pair from the authoritative server side.
     *
     * @param level interacting player's world
     * @param player player opening the example
     * @param hand hand holding the token
     * @return successful interaction without consuming the token
     */
    @Override
    //? if >=1.21.2 {
    /*public InteractionResult use(Level level, Player player, InteractionHand hand) {
    *///?} else {
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    //?}
        if (!level.isClientSide()) {
            player.openMenu(new SimpleMenuProvider(
                    (windowId, inventory, openingPlayer) -> RegisteredContent.menu(AnnotatedMenu.class, windowId, inventory),
                    Component.translatable("menu.sdmal.annotated_menu")));
        }
        //? if >=1.21.2 {
        /*return InteractionResult.SUCCESS;
        *///?} else {
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
        //?}
    }
}
