package dev.sixik.sdmal.example.network;

import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkPacket;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;

import net.minecraft.core.BlockPos;

/**
 * Client-to-server example: {@code new RequestGeneratorStatusPacket(position).send()}.
 *
 * @param position requested generator coordinate in the sender's current dimension
 */
@NetworkPacket(id = "sdmal:request_generator_status", side = Side.SERVER)
public record RequestGeneratorStatusPacket(BlockPos position) implements Packet {
    /**
     * Rejects unloaded or distant targets before querying the ECS projection.
     *
     * @param context authenticated receiving connection on the server game thread
     */
    @Override
    public void handle(PacketContext context) {
        if (position == null) return;
        var player = context.sender();
        if (player.distanceToSqr(
                        position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5)
                > 64.0) return;
        var level = context.level();
        if (!level.hasChunkAt(position)) return;
        if (!(level.getBlockEntity(position) instanceof GeneratorBlockEntity generator)) return;
        var state = generator.renderState();
        if (state == null) return;
        context.reply(new GeneratorStatusPacket(position, state.energy(), state.capacity()));
    }
}
