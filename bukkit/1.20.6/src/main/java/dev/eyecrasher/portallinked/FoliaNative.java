// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** The pinned Folia 1.20.6 async API, loaded only on Folia. */
final class FoliaNative {
    private FoliaNative() {}

    private static Object handle(Player player) throws ReflectiveOperationException {
        return player.getClass().getMethod("getHandle").invoke(player);
    }

    /** Native teleport sequence changes even when Folia omits Bukkit events. */
    static Integer teleportVersion(Player player) {
        try {
            Object connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection").get(handle(player));
            Field version = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl").getDeclaredField("awaitingTeleport");
            version.setAccessible(true);
            return version.getInt(connection);
        } catch (ReflectiveOperationException ex) { return null; }
    }

    /** Hold a crossing whose native readiness was already accepted by the caller. */
    static boolean hold(Player player, Location contact) {
        try {
            Object entity = handle(player);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            int delay = (int) entity.getClass().getMethod("getPortalWaitTime").invoke(entity);
            Field time = entityType.getDeclaredField("portalTime"); time.setAccessible(true);
            time.setInt(entity, delay - 1);
            return true;
        } catch (ReflectiveOperationException ex) { return false; }
    }

    static boolean resume(Player player, Location contact, World target, Runnable completed) {
        try {
            Object entity = handle(player);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> levelType = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> positionType = Class.forName("net.minecraft.core.BlockPos");
            Object position = positionType.getConstructor(int.class, int.class, int.class)
                    .newInstance(contact.getBlockX(), contact.getBlockY(), contact.getBlockZ());
            Class<?> portalType = Class.forName("net.minecraft.world.entity.Entity$PortalType");
            Object destination = target.getClass().getMethod("getHandle").invoke(target);
            Consumer<Object> callback = moved -> completed.run();
            Field entry = entityType.getDeclaredField("portalEntrancePos"); entry.setAccessible(true);
            entry.set(entity, position);
            Method resume = entityType.getDeclaredMethod("portalToAsync", levelType, boolean.class, portalType, Consumer.class);
            resume.setAccessible(true);
            return (boolean) resume.invoke(entity, destination, false, portalType.getField("NETHER").get(null), callback);
        } catch (ReflectiveOperationException ex) { return false; }
    }

    /** Invoke Folia's native completion callback before the next player tick/save. */
    static boolean teleport(Player player, Location destination, Runnable completed) {
        Object entity = null, previousEntry = null;
        Field entered = null;
        try {
            entity = handle(player);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> playerType = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> levelType = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> vectorType = Class.forName("net.minecraft.world.phys.Vec3");
            Object from = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object to = destination.getWorld().getClass().getMethod("getHandle").invoke(destination.getWorld());
            Object position = vectorType.getConstructor(double.class, double.class, double.class)
                    .newInstance(destination.getX(), destination.getY(), destination.getZ());
            Class<?> portalType = Class.forName("net.minecraft.world.entity.Entity$PortalType");
            Method before = playerType.getDeclaredMethod("prePortalLogic", levelType, levelType, portalType);
            before.setAccessible(true);
            entered = playerType.getDeclaredField("enteredNetherPosition"); entered.setAccessible(true);
            previousEntry = entered.get(entity);
            Object nether = portalType.getField("NETHER").get(null);
            Method teleport = entityType.getMethod("teleportAsync", levelType, vectorType, Float.class, Float.class,
                    vectorType, PlayerTeleportEvent.TeleportCause.class, long.class, Consumer.class);
            Consumer<Object> callback = moved -> completed.run();
            before.invoke(entity, from, to, nether);
            long flags = entityType.getField("TELEPORT_FLAG_LOAD_CHUNK").getLong(null);
            boolean accepted = (boolean) teleport.invoke(entity, to, position, destination.getYaw(), destination.getPitch(),
                    vectorType.getField("ZERO").get(null), PlayerTeleportEvent.TeleportCause.NETHER_PORTAL, flags, callback);
            if (!accepted) entered.set(entity, previousEntry);
            return accepted;
        } catch (ReflectiveOperationException ex) {
            if (entered != null && entity != null) {
                try { entered.set(entity, previousEntry); } catch (IllegalAccessException ignored) {}
            }
            return false;
        }
    }
}
