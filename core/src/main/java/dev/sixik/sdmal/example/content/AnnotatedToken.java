package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.world.item.Item;

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
}
