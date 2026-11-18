package dev.sixik.sdmal.example.content.client;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Reuses vanilla armor-stand visuals for the automatically registered example entity.
 */
@RegisterContent(id = "sdmal:annotated_entity")
public final class AnnotatedEntityRenderer extends ArmorStandRenderer {
    /**
     * @param context native model, resource, and renderer context provided during client setup
     */
    public AnnotatedEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }
}
