package dev.sixik.sdmal.api.network;

import java.util.Objects;
import java.util.concurrent.Executor;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * Receiving play connection; player identity comes from the loader, never the payload.
 */
public final class PacketContext {
    /**
     * Logical receiving side.
     */
    private final Side side;
    /**
     * Connected local client player or authenticated server sender.
     */
    private final Player player;
    /**
     * Receiving game-thread executor.
     */
    private final Executor executor;

    /**
     * Creates a loader-owned context.
     *
     * @param side     receiving side
     * @param player   authenticated connection player
     * @param executor receiving game executor
     */
    @ApiStatus.Internal
    public PacketContext(Side side, Player player, Executor executor) {
        this.side = Objects.requireNonNull(side, "side");
        this.player = Objects.requireNonNull(player, "player");
        this.executor = Objects.requireNonNull(executor, "executor");
        if ((side == Side.SERVER) != (player instanceof ServerPlayer))
            throw new IllegalArgumentException("Player does not match receiving side " + side);
    }

    /**
     * @return logical receiving side
     */
    public Side side() {
        return side;
    }

    /**
     * @return authenticated sender on server, or local player on client
     */
    public Player player() {
        return player;
    }

    /**
     * Returns the authoritative server-side sender.
     *
     * @return sending ServerPlayer
     * @throws IllegalStateException for a CLIENT packet
     */
    public ServerPlayer sender() {
        if (side != Side.SERVER) throw new IllegalStateException("CLIENT packets have no server sender");
        return (ServerPlayer) player;
    }

    /**
     * @return player's current receiving world
     */
    public Level level() {
        //? if >=1.20 {
        return player.level();
        //?} else {
        /*return player.level;
         *///?}
    }

    /**
     * Schedules additional work on the same game executor; handle itself is already on that thread.
     *
     * @param work non-null work
     */
    public void enqueueWork(Runnable work) {
        executor.execute(Objects.requireNonNull(work, "work"));
    }

    /**
     * Sends a packet back through this connection, enforcing the opposite receiving side.
     *
     * @param packet reply message
     */
    public void reply(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (side == Side.SERVER) {
            packet.send(player);
            return;
        }
        packet.send();
    }
}
