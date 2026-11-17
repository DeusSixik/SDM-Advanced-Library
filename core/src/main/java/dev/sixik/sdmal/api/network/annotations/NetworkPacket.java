package dev.sixik.sdmal.api.network.annotations;

import dev.sixik.sdmal.api.network.Side;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indexes a Packet automatically at compilation; requires the SDMAL annotation processor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NetworkPacket {
    /**
     * @return stable lowercase namespaced id, for example {@code mymod:example}
     */
    String id();

    /**
     * @return logical side on which handle executes
     */
    Side side();
}
