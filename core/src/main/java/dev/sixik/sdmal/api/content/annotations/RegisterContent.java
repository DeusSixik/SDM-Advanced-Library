package dev.sixik.sdmal.api.content.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers Item, Block, BlockEntity, Entity, AbstractContainerMenu, or ContentTab from the
 * compiler-produced common index. AbstractContainerScreen and EntityRenderer declarations go
 * exclusively into a client index and bind to the menu/entity with the same id. Plain Screen
 * subclasses register a client-only factory accessible through RegisteredScreens. A block entity
 * gets a default block and block item unless explicit classes share its id.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterContent {
    /**
     * @return stable lowercase namespaced registry id
     */
    String id();

    /**
     * @return target creative tab id, or empty to omit the item from creative tabs
     */
    String tab() default "";

    /**
     * @return ascending position among automatically added entries; equal positions sort by id
     */
    int ordinal() default 0;
}
