package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.ContentTab;
import dev.sixik.sdmal.api.content.RegisteredContent;
import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Optional customization for a tab otherwise synthesized from the tab ids on content annotations.
 */
@RegisterContent(id = "sdmal:annotated_content")
public final class AnnotatedTab implements ContentTab {
    /**
     * Creates a stateless customization object.
     */
    public AnnotatedTab() {
    }

    /**
     * @return localized example tab title
     */
    @Override
    public Component title() {
        return Component.translatable("itemGroup.sdmal.annotated_content");
    }

    /**
     * @return the registered token icon, resolved lazily after the item registry completes
     */
    @Override
    public ItemStack icon() {
        return RegisteredContent.item(AnnotatedToken.class).getDefaultInstance();
    }
}
