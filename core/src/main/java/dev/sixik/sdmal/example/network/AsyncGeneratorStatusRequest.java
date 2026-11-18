package dev.sixik.sdmal.example.network;

import dev.sixik.sdmal.api.network.AsyncRequest;
import dev.sixik.sdmal.api.network.AsyncResponder;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkRequest;
import dev.sixik.sdmal.example.ecs.blockentity.GeneratorBlockEntity;
import net.minecraft.core.BlockPos;

/**
 * Async C2S example: a client request waits for a typed server response instead of using a fire-and-forget packet.
 */
@NetworkRequest(id = "sdmal:async_generator_status", side = Side.SERVER, response = GeneratorStatusPacket.class)
public record AsyncGeneratorStatusRequest(BlockPos position) implements AsyncRequest<GeneratorStatusPacket> {
    /**
     * Reads the authoritative generator projection and completes the response on the server game thread.
     *
     * @param context   authenticated server connection
     * @param responder exactly-once response handle
     */
    @Override
    public void handle(PacketContext context, AsyncResponder<GeneratorStatusPacket> responder) {
        if (position == null || context.sender().distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5) > 64.0) {
            responder.fail("Generator is out of reach");
            return;
        }
        if (!context.level().hasChunkAt(position)) {
            responder.fail("Generator chunk is unavailable");
            return;
        }
        if (!(context.level().getBlockEntity(position) instanceof GeneratorBlockEntity generator)) {
            responder.fail("Generator not found");
            return;
        }
        var state = generator.renderState();
        if (state == null) {
            responder.fail("Generator ECS is unavailable");
            return;
        }
        responder.complete(new GeneratorStatusPacket(position, state.energy(), state.capacity()));
    }
}
