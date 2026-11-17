package dev.sixik.sdmal.example.content;

import dev.sixik.sdmal.api.content.RegisteredEntityBlock;
import dev.sixik.sdmal.api.content.annotations.RegisterContent;

/**
 * Replaces the generated block of AnnotatedMachineEntity while inheriting its native entity
 * factory. The tab and ordinal are inherited from the entity because this annotation only declares
 * the id.
 */
@RegisterContent(id = "sdmal:annotated_machine")
public final class AnnotatedMachine extends RegisteredEntityBlock {
    /**
     * @param properties injected keyed block properties
     */
    public AnnotatedMachine(Properties properties) {
        super(properties.strength(4.0f));
    }
}
