package dev.sixik.sdmal.example.ecs.blockentity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonParser;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.minecraft.WorldObjectManagerApi;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies server-authoritative click diagnostics through each version's real interaction overrides.
 */
class GeneratorInteractionTest {
    /**
     * Coordinate of the controlled generator fixture.
     */
    private static final BlockPos POS = new BlockPos(6, 64, 2);
    /**
     * Controlled worlds whose registered managers must be closed after each test.
     */
    private final List<Level> worlds = new ArrayList<>();

    /**
     * Initializes actual Minecraft registries and ECS component services.
     */
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldObjectComponentRegistry.instance().registerServices();
    }

    /**
     * Releases test world registrations.
     */
    @AfterEach void cleanup() {
        worlds.forEach(WorldObjectManagerApi::unregisterWorld);
    }

    /**
     * A right click displays committed server energy, capacity and the manager's current tick.
     * @throws ReflectiveOperationException if vanilla interaction signatures change
     */
    @Test void rightClick_server_reportsCurrentEnergyAndTick() throws ReflectiveOperationException {
        var shell = shell();
        var level = world(false, shell);
        shell.setLevel(level);
        WorldObjectManagerApi.getManager(level).tickAll();
        var player = mock(Player.class);
        click(level, player, false);
        var message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendSystemMessage(message.capture());
        var contents = assertInstanceOf(TranslatableContents.class, message.getValue().getContents());
        assertEquals("message.sdmal.ecs_generator.energy", contents.getKey());
        assertArrayEquals(new Object[] {1, 1000, 1L}, contents.getArgs());
        shell.setRemoved();
    }

    /**
     * Held items use the version's item interaction path and remain untouched by debugging.
     * @throws ReflectiveOperationException if vanilla interaction signatures change
     */
    @Test void rightClick_withItem_reportsOnceWithoutConsumingItem() throws ReflectiveOperationException {
        var shell = shell();
        var level = world(false, shell);
        shell.setLevel(level);
        var player = mock(Player.class);
        ItemStack stack = click(level, player, true);
        verify(player, times(1)).sendSystemMessage(any(Component.class));
        assertEquals(3, stack.getCount());
        verify(stack).getCount();
        verifyNoMoreInteractions(stack);
        shell.setRemoved();
    }

    /**
     * Client prediction never prints duplicate or delayed mirror energy.
     * @throws ReflectiveOperationException if vanilla interaction signatures change
     */
    @Test void rightClick_client_doesNotSendOrReadBlockEntity() throws ReflectiveOperationException {
        var level = world(true, null);
        var player = mock(Player.class);
        click(level, player, false);
        verifyNoInteractions(player);
        verify(level, never()).getBlockEntity(any(BlockPos.class));
    }

    /**
     * An unresolved or not-yet-bound composition reports its inactive state without throwing.
     */
    @Test void debug_unboundServerShell_reportsInactiveState() throws ReflectiveOperationException {
        var shell = shell();
        var level = world(false, shell);
        shell.setLevel(level);
        shell.releaseEcsObject();
        var player = mock(Player.class);
        shell.sendDebugInfo(player);
        var message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendSystemMessage(message.capture());
        var contents = assertInstanceOf(TranslatableContents.class, message.getValue().getContents());
        assertEquals("message.sdmal.ecs_generator.inactive", contents.getKey());
    }

    /**
     * Both inventory model formats and both locales reference the registered example identifier.
     * @throws Exception if an example resource cannot be loaded
     */
    @Test void resources_generator_hasBlockItemModelsAndLocalizedDiagnostics() throws Exception {
        for (String path : List.of("assets/sdmal/blockstates/ecs_generator.json",
                "assets/sdmal/models/block/ecs_generator.json", "assets/sdmal/models/item/ecs_generator.json",
                "assets/sdmal/items/ecs_generator.json", "data/sdmal/loot_tables/blocks/ecs_generator.json",
                "data/sdmal/loot_table/blocks/ecs_generator.json")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, path);
                assertTrue(JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).isJsonObject());
            }
        }
        for (String locale : List.of("en_us", "ru_ru")) {
            try (var input = getClass().getClassLoader().getResourceAsStream("assets/sdmal/lang/" + locale + ".json")) {
                assertNotNull(input);
                var lang = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(lang.has("block.sdmal." + GeneratorContent.PATH));
                String message = lang.get("message.sdmal.ecs_generator.energy").getAsString();
                assertEquals(3, message.split("%s", -1).length - 1);
            }
        }
    }

    /**
     * Invokes the actual version-selected override without constructing a block in frozen registries.
     * @param level receiving world
     * @param player interacting player
     * @param withItem whether to exercise the split item interaction API where available
     * @return original held stack, allowing mutation checks
     * @throws ReflectiveOperationException if Minecraft method signatures change
     */
    private static ItemStack click(Level level, Player player, boolean withItem) throws ReflectiveOperationException {
        var block = mock(GeneratorBlock.class, CALLS_REAL_METHODS);
        var stack = mock(ItemStack.class);
        when(stack.getCount()).thenReturn(3);
        var methods = Arrays.asList(EcsExampleBlock.class.getDeclaredMethods());
        var method = methods.stream().filter(m -> m.getName().equals("use")).findFirst().orElse(null);
        if (method != null) {
            method.setAccessible(true);
            method.invoke(block, Blocks.FURNACE.defaultBlockState(), level, POS, player,
                InteractionHand.MAIN_HAND, mock(BlockHitResult.class));
            return stack;
        }
        String name = withItem ? "useItemOn" : "useWithoutItem";
        method = methods.stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        if (withItem) method.invoke(block, stack, Blocks.FURNACE.defaultBlockState(), level, POS,
            player, InteractionHand.MAIN_HAND, mock(BlockHitResult.class));
        else method.invoke(block, Blocks.FURNACE.defaultBlockState(), level, POS, player, mock(BlockHitResult.class));
        return stack;
    }

    /**
     * Creates a shell with a valid registered vanilla fixture type on every supported version.
     * @return unbound shell
     * @throws ReflectiveOperationException if the vanilla furnace registry holder changes
     */
    private static GeneratorBlockEntity shell() throws ReflectiveOperationException {
        Class<?> holder;
        try { holder = Class.forName("net.minecraft.world.level.block.entity.BlockEntityTypes"); }
        catch (ClassNotFoundException legacy) { holder = BlockEntityType.class; }
        var type = (BlockEntityType<?>) holder.getField("FURNACE").get(null);
        return new GeneratorBlockEntity(type, POS, Blocks.FURNACE.defaultBlockState());
    }

    /**
     * Mocks the Level boundary while retaining real managers and component simulation.
     * @param client side flag
     * @param shell installed generator, or null for client interaction-only checks
     * @return registered controlled world
     */
    private Level world(boolean client, GeneratorBlockEntity shell) {
        var level = mock(Level.class);
        when(level.isClientSide()).thenReturn(client);
        when(level.getBlockEntity(POS)).thenReturn(shell);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        when(level.hasChunkAt(POS)).thenReturn(true);
        when(level.shouldTickBlocksAt(POS)).thenReturn(true);
        when(level.getBlockState(POS)).thenReturn(Blocks.FURNACE.defaultBlockState());
        WorldObjectManagerApi.registerWorld(level);
        worlds.add(level);
        return level;
    }
}
