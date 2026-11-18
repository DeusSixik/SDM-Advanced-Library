package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.annotations.EntitySettings;
import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

/**
 * Summonable world entity demonstrating automatic EntityType, attributes, and client renderer binding.
 */
@RegisterContent(id = "sdmal:annotated_entity")
@EntitySettings(width = 0.5f, height = 1.975f, trackingRange = 8)
public final class AnnotatedWorldEntity extends ArmorStand {
    /**
     * @param type  automatically registered native entity type
     * @param level world receiving the entity
     */
    public AnnotatedWorldEntity(EntityType<? extends ArmorStand> type, Level level) {
        super(type, level);
    }

    /**
     * Supplies the conventional public factory consumed by native attribute registration events.
     *
     * @return basic armor-stand-compatible living attributes
     */
    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes();
    }
}
