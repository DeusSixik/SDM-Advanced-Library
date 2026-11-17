package dev.sixik.sdmal.core.content;

import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import dev.sixik.sdmal.api.content.RegisteredEntityBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.EntityBlock;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validated deterministic startup plan; no Minecraft content instances are constructed here.
 */
final class ContentPlan {
    /**
     * Groups sharing registry ids; a block and its block entity deliberately share one group.
     */
    final Map<String, Group> groups = new TreeMap<>();

    /**
     * Declared classes mapped to their automatic ids.
     */
    final Map<Class<?>, String> classes = new HashMap<>();

    /**
     * Tab ids to ordered content groups, including implicitly requested tabs.
     */
    final Map<String, Group[]> entries;

    /**
     * Validates all classes before a loader is allowed to enlist registry entries.
     *
     * @param types indexed classes from every dependency
     */
    ContentPlan(Collection<Class<?>> types) {
        for (var type : types) {
            var annotation = type.getDeclaredAnnotation(RegisterContent.class);
            if (annotation == null)
                throw new IllegalArgumentException("Missing @RegisterContent on " + type.getName());
            checkId(annotation.id());
            if (!annotation.tab().isEmpty()) checkId(annotation.tab());
            if (type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())
                    || !Modifier.isPublic(type.getModifiers())
                    || type.getTypeParameters().length != 0
                    || (type.isMemberClass() && !Modifier.isStatic(type.getModifiers())))
                throw new IllegalArgumentException(
                        "Content must be a public concrete, non-generic class: " + type.getName());
            var kind = ContentKind.of(type);
            if (kind == ContentKind.TAB && !annotation.tab().isEmpty())
                throw new IllegalArgumentException(
                        "ContentTab cannot itself belong to another tab: " + type.getName());
            var group = groups.computeIfAbsent(annotation.id(), Group::new);
            var declaration = new Declaration(type, annotation.tab(), annotation.ordinal());
            var previous = group.declarations.putIfAbsent(kind, declaration);
            if (previous != null && previous.type() != type)
                throw new IllegalArgumentException(
                        "Duplicate "
                                + kind
                                + " id "
                                + annotation.id()
                                + ": "
                                + previous.type().getName()
                                + " and "
                                + type.getName());
            classes.put(type, annotation.id());
        }
        var byTab = new TreeMap<String, java.util.ArrayList<Group>>();
        for (var group : groups.values()) {
            var entity = group.get(ContentKind.BLOCK_ENTITY);
            var block = group.get(ContentKind.BLOCK);
            var item = group.get(ContentKind.ITEM);
            if (entity == null && block != null && RegisteredEntityBlock.class.isAssignableFrom(block.type()))
                throw new IllegalArgumentException("RegisteredEntityBlock requires an annotated BlockEntity with the same id: " + group.id);
            if (entity != null
                    && block != null
                    && !EntityBlock.class.isAssignableFrom(block.type()))
                throw new IllegalArgumentException(
                        "Block sharing a BlockEntity id must implement EntityBlock or extend"
                            + " RegisteredEntityBlock: "
                                + group.id);
            if (item != null && BlockItem.class.isAssignableFrom(item.type()) && !group.hasBlock())
                throw new IllegalArgumentException(
                        "Annotated BlockItem has no block with the same id: " + group.id);
            if (entity != null
                    && block != null
                    && !entity.tab().isEmpty()
                    && !block.tab().isEmpty()
                    && (!entity.tab().equals(block.tab()) || entity.ordinal() != block.ordinal()))
                throw new IllegalArgumentException(
                        "Conflicting tab/ordinal on Block and BlockEntity sharing " + group.id);
            var presentation =
                    item != null
                            ? item
                            : block != null && !block.tab().isEmpty()
                                    ? block
                                    : entity != null ? entity : block;
            group.tab = presentation == null ? "" : presentation.tab();
            group.ordinal = presentation == null ? 0 : presentation.ordinal();
            if (!group.tab.isEmpty())
                byTab.computeIfAbsent(group.tab, key -> new java.util.ArrayList<>()).add(group);
        }
        entries = new TreeMap<>();
        byTab.forEach(
                (tab, groups) -> {
                    groups.sort(
                            Comparator.comparingInt((Group group) -> group.ordinal)
                                    .thenComparing(group -> group.id));
                    entries.put(tab, groups.toArray(Group[]::new));
                });
    }

    /**
     * @param id registry identifier
     * @throws IllegalArgumentException if it is not lowercase and namespaced
     */
    private static void checkId(String id) {
        if (id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Expected lowercase namespaced content id: " + id);
    }

    /**
     * Immutable declaration metadata.
     *
     * @param type user implementation class
     * @param tab selected creative tab, or empty
     * @param ordinal ascending position
     */
    record Declaration(Class<?> type, String tab, int ordinal) {}

    /**
     * Corresponding native entries plus presentation metadata for their one inventory item.
     */
    static final class Group {
        /**
         * Shared native registry identifier.
         */
        final String id;

        /**
         * At most one implementation of each native kind.
         */
        final Map<ContentKind, Declaration> declarations =
                new java.util.EnumMap<>(ContentKind.class);

        /**
         * Effective item tab, empty if unlisted.
         */
        String tab;

        /**
         * Effective item position in its tab.
         */
        int ordinal;

        /**
         * @param id shared native identifier
         */
        Group(String id) {
            this.id = id;
        }

        /**
         * @param kind native category
         * @return declared implementation or null
         */
        Declaration get(ContentKind kind) {
            return declarations.get(kind);
        }

        /**
         * @return whether an explicit or generated block exists
         */
        boolean hasBlock() {
            return get(ContentKind.BLOCK) != null || get(ContentKind.BLOCK_ENTITY) != null;
        }

        /**
         * @return whether an explicit or generated inventory item exists
         */
        boolean hasItem() {
            return get(ContentKind.ITEM) != null || hasBlock();
        }
    }
}
