package dev.sixik.sdmal.api.network.annotations;

import dev.sixik.sdmal.api.network.Side;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indexes a typed asynchronous request. The annotation's side is where the request handler runs;
 * {@code response} is automatically serialized and returned to the requester.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NetworkRequest {
    /**
     * @return stable lowercase namespaced request id
     */
    String id();

    /**
     * @return receiving side on which {@code handle} executes
     */
    Side side();

    /**
     * @return concrete response record/class, or {@code Void.class} for an acknowledgement without data
     */
    Class<?> response();

    /**
     * @return caller timeout in milliseconds, strictly positive and at most 86400000 (one day)
     */
    long timeoutMillis() default 30_000L;
}
