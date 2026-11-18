package dev.sixik.sdmal.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkPacket;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class ComponentPacketSerializationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void packetComponentField_roundTripsRegisteredComponentUsingOnlyEcsSyncFields() {
        ComponentRegistry.instance().registerAnnotated(ShopComponent.class);
        var registry = new PacketRegistry(List.of(ShopSnapshot.class));
        var component = new ShopComponent();
        component.energy(725);
        component.serverCache(91);
        var source = new ShopSnapshot(UUID.randomUUID(), component);

        ShopSnapshot decoded = (ShopSnapshot) registry.decode(
                registry.encode(source, Side.CLIENT), Side.CLIENT);

        assertEquals(source.shopId(), decoded.shopId());
        assertInstanceOf(ShopComponent.class, decoded.component());
        assertNotSame(component, decoded.component());
        assertEquals(725, decoded.component().energy());
        assertEquals(17, decoded.component().serverCache());
    }

    @Test
    void packetComponentField_requiresRegisteredConcreteType() {
        assertThrows(RuntimeException.class,
                () -> NetworkSerialization.packetCodec(UnregisteredSnapshot.class));
    }

    @EcsComponent(id = "sdmal_test:packet_shop")
    public static final class ShopComponent extends Component {
        @EcsSync("energy")
        private int energy;

        private int serverCache = 17;

        public ShopComponent() {}

        public int energy() {
            return energy;
        }

        public void energy(int energy) {
            this.energy = energy;
        }

        public int serverCache() {
            return serverCache;
        }

        public void serverCache(int serverCache) {
            this.serverCache = serverCache;
        }
    }

    @NetworkPacket(id = "sdmal_test:shop_snapshot", side = Side.CLIENT)
    private record ShopSnapshot(UUID shopId, ShopComponent component) implements Packet {
        @Override
        public void handle(PacketContext context) {}
    }

    private static final class UnregisteredComponent extends Component {
        @EcsSync("value")
        private int value;

        public UnregisteredComponent() {}
    }

    private record UnregisteredSnapshot(UnregisteredComponent component) implements Packet {
        @Override
        public void handle(PacketContext context) {}
    }
}
