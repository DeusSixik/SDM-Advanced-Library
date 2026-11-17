/**
 * Explicit association between Minecraft worlds and ECS managers. The impl mixins provide world
 * construction and tick hooks; custom integrations may register and tick managers explicitly.
 * Unload hooks must release managers.
 */
package dev.sixik.sdmal.api.ecs.minecraft;
