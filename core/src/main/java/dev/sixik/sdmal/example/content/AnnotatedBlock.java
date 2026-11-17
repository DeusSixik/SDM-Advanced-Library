package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;

import net.minecraft.world.level.block.Block;

/**
 * Ordinary block automatically receiving a matching BlockItem.
 */
@RegisterContent(id = "sdmal:annotated_block", tab = "sdmal:annotated_content", ordinal = 0)
public final class AnnotatedBlock extends Block {
    /**
     * @param properties injected keyed native block properties
     */
    public AnnotatedBlock(Properties properties) {
        super(properties.strength(2.0f));
    }
}
