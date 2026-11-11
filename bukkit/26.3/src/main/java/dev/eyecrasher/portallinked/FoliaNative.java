// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** The pinned Folia 26.3 async API, loaded only on Folia. */
final class FoliaNative {
    private FoliaNative() {}

    private static Object handle(Player player) throws ReflectiveOperationException {
        return player.getClass().getMethod("getHandle").invoke(player);
    }

    /** Keep native travel pending without taking ownership of event cancellation. */
    static boolean hold(Player player, Location contact) {
        try {
            Object entity = handle(player);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Field processField = entityType.getDeclaredField("portalProcess");
            processField.setAccessible(true);
            Object process = processField.get(entity);
            Object block = Class.forName("net.minecraft.world.level.block.Blocks").getField("NETHER_PORTAL").get(null);
            Object level = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            int delay = (int) block.getClass().getMethod("getPortalTransitionTime",
                    Class.forName("net.minecraft.server.level.ServerLevel"), entityType).invoke(block, level, entity);
            if (process == null) {
                Class<?> positionType = Class.forName("net.minecraft.core.BlockPos");
                Object position = positionType.getConstructor(int.class, int.class, int.class)
                        .newInstance(contact.getBlockX(), contact.getBlockY(), contact.getBlockZ());
                process = Class.forName("net.minecraft.world.entity.PortalProcessor")
                        .getConstructor(Class.forName("net.minecraft.world.level.block.Portal"), positionType).newInstance(block, position);
                processField.set(entity, process);
            }
            Field time = process.getClass().getDeclaredField("portalTime"); time.setAccessible(true);
            // Native code compares the old value before incrementing it. -1 also
            // holds zero-delay creative portals for this contact only.
            time.setInt(process, delay - 1);
            return true;
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
            Class<?> transition = Class.forName("net.minecraft.world.level.portal.TeleportTransition");
            Class<?> effectsType = Class.forName("net.minecraft.world.level.portal.TeleportTransition$PostTeleportTransition");
            Object sound = transition.getField("PLAY_PORTAL_SOUND").get(null);
            Object ticket = transition.getField("PLACE_PORTAL_TICKET").get(null);
            Method effect = effectsType.getMethod("onTransition", entityType);
            Method teleport = entityType.getMethod("teleportAsync", levelType, vectorType, Float.class, Float.class,
                    vectorType, PlayerTeleportEvent.TeleportCause.class, long.class, Consumer.class);
            Consumer<Object> callback = moved -> {
                try {
                    effect.invoke(sound, moved);
                    effect.invoke(ticket, moved);
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Folia portal completion failed", ex); }
                completed.run();
            };
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
