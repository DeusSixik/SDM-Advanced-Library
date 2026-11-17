package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.world.item.Item;

/**
 * Available through /give, with no automatic creative-tab membership because tab is omitted.
 */
@RegisterContent(id = "sdmal:annotated_hidden_item")
public final class AnnotatedHiddenItem extends Item {
    /**
     * @param properties injected native item properties
     */
    public AnnotatedHiddenItem(Properties properties) {
        super(properties);
    }
}
