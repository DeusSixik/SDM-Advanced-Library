package dev.sixik.sdmal.example.network;

import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Server-to-client example: {@code new GeneratorStatusPacket(pos, energy, capacity).send(player)}.
 *
 * @param position generator coordinate
 * @param energy   stored energy units
 * @param capacity maximum energy units
 */
@NetworkPacket(id = "sdmal:generator_status", side = Side.CLIENT)
public record GeneratorStatusPacket(
        BlockPos position, @EcsSync("energy") long energy, long capacity) implements Packet {
    /**
     * Displays the server projection through the common player API, without client-only imports.
     *
     * @param context local player on the client game thread
     */
    @Override
    public void handle(PacketContext context) {
        context.player()
                .sendSystemMessage(
                        Component.literal(
                                "ECS " + position + ": " + energy + " / " + capacity + " energy"));
    }
}
