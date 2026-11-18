package dev.sixik.sdmal.core.content;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Temporarily restores constructor-only registry state after vanilla bootstrap freezes test
 * registries. No values are registered; every modified field and queued component initializer is
 * restored on close. Actual native publication is verified separately by loader launch probes.
 */
final class ContentRegistryScope implements AutoCloseable {
    /**
     * Original native field values, restored in reverse order.
     */
    private final List<SavedField> saved = new ArrayList<>();

    /**
     * Native pending component initializers on versions with data-driven item defaults.
     */
    private final List<?> initializers;

    /**
     * Initial native initializer count, excluding this test's temporary constructors.
     */
    private final int initializerCount;

    /**
     * Reopens constructor holder caches without registering or replacing native entries.
     *
     * @throws ReflectiveOperationException if native test fixture internals change
     */
    ContentRegistryScope() throws ReflectiveOperationException {
        Class<?> registries;
        try {
            registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
        } catch (ClassNotFoundException legacy) {
            registries = Class.forName("net.minecraft.core.Registry");
        }
        var mapped = Class.forName("net.minecraft.core.MappedRegistry");
        var frozen = mapped.getDeclaredField("frozen");
        Field intrusive;
        try { intrusive = mapped.getDeclaredField("unregisteredIntrusiveHolders"); }
        catch (NoSuchFieldException legacy) { intrusive = mapped.getDeclaredField("intrusiveHolderCache"); }
        for (var name : List.of("BLOCK", "ITEM", "ENTITY_TYPE")) {
            var registry = registries.getField(name).get(null);
            replace(frozen, registry, false);
            replace(intrusive, registry, new IdentityHashMap<>());
        }
        List<?> pending;
        try {
            var nativeInitializers = registries.getField("DATA_COMPONENT_INITIALIZERS").get(null);
            var field = nativeInitializers.getClass().getDeclaredField("initializers");
            field.setAccessible(true);
            pending = (List<?>) field.get(nativeInitializers);
        } catch (NoSuchFieldException older) {
            pending = null;
        }
        initializers = pending;
        initializerCount = pending == null ? 0 : pending.size();
        bind(Items.CHEST, true);
        bind(Items.DIAMOND, true);
        bind(Items.STICK, true);
    }

    /**
     * Binds empty test defaults for newly constructed, deliberately unregistered native items.
     *
     * @param item constructed native item
     * @throws ReflectiveOperationException if the native holder API changes
     */
    static void bindNewItem(Item item) throws ReflectiveOperationException {
        bind(item, null);
    }

    /**
     * Supplies test defaults only on versions whose item holders require a later data-component
     * bind phase.
     *
     * @param item test or vanilla item
     * @param remember whether to restore a preexisting vanilla holder
     * @throws ReflectiveOperationException if the native component API changes
     */
    private void bind(Item item, boolean remember) throws ReflectiveOperationException {
        bind(item, remember ? this : null);
    }

    /**
     * @param item item whose identity-only stack is used by these tests
     * @param scope restoration scope for existing native holders, or null for a new temporary item
     * @throws ReflectiveOperationException if native component internals change
     */
    private static void bind(Item item, ContentRegistryScope scope)
            throws ReflectiveOperationException {
        Field components;
        try {
            components =
                    Class.forName("net.minecraft.core.Holder$Reference")
                            .getDeclaredField("components");
        } catch (NoSuchFieldException older) {
            return;
        }
        components.setAccessible(true);
        var holder = Item.class.getMethod("builtInRegistryHolder").invoke(item);
        if (components.get(holder) != null) return;
        var empty =
                Class.forName("net.minecraft.core.component.DataComponentMap")
                        .getField("EMPTY")
                        .get(null);
        if (scope == null) components.set(holder, empty);
        else scope.replace(components, holder, empty);
    }

    /**
     * @param field native field to replace
     * @param owner native object
     * @param value temporary value
     * @throws IllegalAccessException if test reflection is rejected
     */
    private void replace(Field field, Object owner, Object value) throws IllegalAccessException {
        field.setAccessible(true);
        saved.add(new SavedField(field, owner, field.get(owner)));
        field.set(owner, value);
    }

    /**
     * Restores every temporarily modified native field and removes this test's pending
     * initializers.
     *
     * @throws IllegalAccessException if native field restoration fails
     */
    @Override
    public void close() throws IllegalAccessException {
        if (initializers != null)
            initializers.subList(initializerCount, initializers.size()).clear();
        for (int index = saved.size() - 1; index >= 0; index--) {
            var entry = saved.get(index);
            entry.field().set(entry.owner(), entry.value());
        }
    }

    /**
     * Original native field value.
     *
     * @param field reflected native field
     * @param owner field owner
     * @param value original field
     *     value
     */
    private record SavedField(Field field, Object owner, Object value) {}
}
