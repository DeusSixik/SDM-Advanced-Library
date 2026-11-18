package dev.sixik.sdmal.core.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Resolves public constructors once, before enlisting any registry changes.
 */
final class ContentConstructors {
    /**
     * Prevents utility construction.
     */
    private ContentConstructors() {}

    /**
     * @param group registry group
     * @param kind declaration category
     * @return typed constructor handle or null for generated content
     */
    static MethodHandle resolve(ContentPlan.Group group, ContentKind kind) {
        var declaration = group.get(kind);
        if (declaration == null) return null;
        var type = declaration.type();
        try {
            return switch (kind) {
                case BLOCK -> constructor(type, Block.class, BlockBehaviour.Properties.class);
                case ITEM ->
                        BlockItem.class.isAssignableFrom(type)
                                ? constructor(type, Item.class, Block.class, Item.Properties.class)
                                : constructor(type, Item.class, Item.Properties.class);
                case TAB -> constructor(type, dev.sixik.sdmal.api.content.ContentTab.class);
                case BLOCK_ENTITY -> entity(type);
                case ENTITY -> constructor(type, Entity.class, EntityType.class, Level.class);
                case MENU -> menu(type);
            };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException(
                    "Unsupported public content constructor on "
                            + type.getName()
                            + ": "
                            + failure.getMessage(),
                    failure);
        }
    }

    /**
     * @param type implementation class
     * @param result native return type
     * @param parameters exact constructor parameters
     * @return adapted public constructor
     * @throws ReflectiveOperationException if the constructor cannot be called
     */
    private static MethodHandle constructor(Class<?> type, Class<?> result, Class<?>... parameters)
            throws ReflectiveOperationException {
        return MethodHandles.publicLookup()
                .findConstructor(type, MethodType.methodType(void.class, parameters))
                .asType(MethodType.methodType(result, parameters));
    }

    /**
     * @param type concrete block entity class
     * @return factory accepting the native type, position and state without argument arrays
     * @throws ReflectiveOperationException if neither supported public constructor exists
     */
    private static MethodHandle entity(Class<?> type) throws ReflectiveOperationException {
        try {
            return constructor(
                    type,
                    BlockEntity.class,
                    BlockEntityType.class,
                    BlockPos.class,
                    BlockState.class);
        } catch (NoSuchMethodException missingInjectedType) {
            return MethodHandles.dropArguments(
                    constructor(type, BlockEntity.class, BlockPos.class, BlockState.class),
                    0,
                    BlockEntityType.class);
        }
    }

    /**
     * Adapts either menu constructor to one injected native-type factory shape.
     *
     * @param type concrete menu implementation
     * @return factory accepting MenuType, window id, and player inventory
     * @throws ReflectiveOperationException if neither public constructor exists
     */
    private static MethodHandle menu(Class<?> type) throws ReflectiveOperationException {
        try {
            return constructor(type, AbstractContainerMenu.class, MenuType.class, int.class, Inventory.class);
        } catch (NoSuchMethodException conventional) {
            return MethodHandles.dropArguments(
                    constructor(type, AbstractContainerMenu.class, int.class, Inventory.class), 0, MenuType.class);
        }
    }

    /**
     * Requires the conventional attributes builder for a living entity, including inherited methods.
     *
     * @param type concrete world entity class
     * @return static createAttributes handle, or null for a nonliving entity
     */
    static MethodHandle attributes(Class<?> type) {
        if (!LivingEntity.class.isAssignableFrom(type)) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findStatic(type, "createAttributes",
                    MethodType.methodType(AttributeSupplier.Builder.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Living content requires public static AttributeSupplier.Builder"
                    + " createAttributes(): " + type.getName(), failure);
        }
    }

    /**
     * @param id failed registry entry
     * @param failure user constructor failure
     * @return actionable startup exception
     */
    static RuntimeException failure(String id, Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        if (failure instanceof LinkageError fatal) throw fatal;
        return new IllegalStateException("Cannot construct registered content " + id, failure);
    }
}
