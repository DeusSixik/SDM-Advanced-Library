package dev.sixik.sdmal.core.ecs.serialization;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.annotations.EcsUseNetworkAdapter;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializers;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

/**
 * Checks typed primitive field access against the original public boxed formats, including volatile access.
 */
class PrimitiveWireTest {
    /**
     * Every native field writer must be byte-identical to its public adapter in both access modes.
     * @throws Exception if a fixture field cannot be reflected
     */
    @Test
    @SuppressWarnings("unchecked")
    void primitiveWriters_plainAndVolatile_matchPublicAdapterBytes() throws Exception {
        var values = new Values();
        var lookup = MethodHandles.privateLookupIn(Values.class, MethodHandles.lookup());
        for (var field : Values.class.getDeclaredFields()) {
            var type = (Class<Object>) EcsTypes.boxed(field.getType());
            EcsNetworkSerializer<Object> serializer = EcsNetworkSerializers.serializer(type);
            var access = lookup.findVarHandle(Values.class, field.getName(), field.getType());
            for (boolean volatileAccess : new boolean[] {false, true}) {
                var member =
                        new EcsNetworkMember(
                                field.getName(),
                                field.toString(),
                                access,
                                serializer,
                                false,
                                volatileAccess,
                                EcsNetworkMember.primitiveKind(field.getType()));
                FriendlyByteBuf expected = new FriendlyByteBuf(Unpooled.buffer());
                FriendlyByteBuf actual = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    serializer.write(expected, field.get(values));
                    member.write(values, actual);
                    assertTrue(
                            ByteBufUtil.equals(expected, actual),
                            field + ", volatile=" + volatileAccess);
                } finally {
                    expected.release();
                    actual.release();
                }
            }
        }
    }

    /**
     * A primitive field with an explicit format override must retain its custom byte layout.
     */
    @Test
    void primitiveField_explicitOverride_keepsCustomFormat() {
        var source = new Custom();
        var copy = new Custom();
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            source.writeNetwork(wire);
            copy.readNetwork(wire);
            assertEquals(source.value, copy.value);
            wire.readerIndex(0);
            wire.readInt();
            assertEquals(1, wire.readVarInt());
            wire.readLong();
            assertEquals(1, wire.readVarInt());
            assertEquals(source.value, wire.readVarInt());
            assertEquals(0, wire.readableBytes());
        } finally {
            wire.release();
        }
    }

    /**
     * Non-cached values and floating special values exercise the complete primitive writer set.
     */
    private static final class Values {
        /**
         * Boolean payload.
         */
        public boolean flag = true;

        /**
         * Signed byte payload.
         */
        public byte small = -100;

        /**
         * Uncached signed short.
         */
        public short medium = 1357;

        /**
         * Non-ASCII UTF-16 code unit.
         */
        public char character = '\u1234';

        /**
         * Uncached signed int.
         */
        public int integer = 1357;

        /**
         * Signed long outside the wrapper cache.
         */
        public long large = Long.MIN_VALUE;

        /**
         * NaN bit pattern matching the buffer's original float codec.
         */
        public float single = Float.NaN;

        /**
         * Double infinity.
         */
        public double decimal = Double.NEGATIVE_INFINITY;
    }

    /**
     * Component deliberately changing an int's default fixed-width encoding.
     */
    private static final class Custom extends AnnotatedWorldObjectComponent {
        /**
         * Explicitly overridden primitive format.
         */
        @EcsSync("value")
        @EcsUseNetworkAdapter(VarInt.class)
        private volatile int value = 1357;
    }

    /**
     * User-owned variable-width format that must not use the native fixed-width fast path.
     */
    public static final class VarInt implements EcsNetworkSerializer<Integer> {
        /**
         * @param wire destination
         * @param value primitive value boxed by the custom adapter API
         */
        @Override
        public void write(FriendlyByteBuf wire, Integer value) {
            wire.writeVarInt(value);
        }

        /**
         * @param wire source
         * @return decoded value
         */
        @Override
        public Integer read(FriendlyByteBuf wire) {
            return wire.readVarInt();
        }

        /**
         * @return stable consumer format id
         */
        @Override
        public String schemaName() {
            return "test:custom-varint/v1";
        }
    }
}
