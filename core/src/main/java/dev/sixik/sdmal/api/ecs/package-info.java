/**
 * World-independent ECS objects, Component data/behaviour and owner-thread lifecycle management.
 * Component is the default base; sync.NetworkComponent adds explicit tracked fields and deltas.
 * Supported public ECS API. Annotations live in {@link dev.sixik.sdmal.api.ecs.annotations}, value
 * adapters in {@link dev.sixik.sdmal.api.ecs.serialization}, and Minecraft integration in {@link
 * dev.sixik.sdmal.api.ecs.minecraft}. Package-private companions implement lifecycle and scheduling
 * without exposing extension points.
 */
package dev.sixik.sdmal.api.ecs;
