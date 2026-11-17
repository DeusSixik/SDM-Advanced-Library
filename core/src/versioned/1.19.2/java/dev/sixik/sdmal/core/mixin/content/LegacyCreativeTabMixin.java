package dev.sixik.sdmal.core.mixin.content;

import dev.sixik.sdmal.core.content.ContentRuntime;

import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds deterministic ordinal ordering to legacy vanilla tabs without a modern creative-tab event.
 */
@Mixin(CreativeModeTab.class)
abstract class LegacyCreativeTabMixin {
    /**
     * @param output rebuilt native item list
     * @param callback mixin callback
     */
    @Inject(method = "fillItemList", at = @At("TAIL"))
    private void sdmal$appendOrderedContent(NonNullList<ItemStack> output, CallbackInfo callback) {
        ContentRuntime.appendLegacy((CreativeModeTab) (Object) this, output);
    }

    /**
     * @param callback native title result
     */
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void sdmal$customTitle(CallbackInfoReturnable<Component> callback) {
        var title = ContentRuntime.legacyTitle((CreativeModeTab) (Object) this);
        if (title != null) callback.setReturnValue(title);
    }
}
