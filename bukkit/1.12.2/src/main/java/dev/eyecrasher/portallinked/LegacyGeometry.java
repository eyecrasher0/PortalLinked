// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Read-only native geometry. Never runs a search, moves an entity or fires a protection event. */
final class LegacyGeometry {
    private LegacyGeometry() {}
    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft.server.v1_12_R1." + name);
    }
    private static Object call(Object value, String name) throws ReflectiveOperationException {
        return value.getClass().getMethod(name).invoke(value);
    }
    static Bounds bounds(Player player) {
        try {
            return new Bounds(call(call(player, "getHandle"), "getBoundingBox"));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    static boolean safeReturn(Player player, PortalRef saved, World world) {
        try {
            Object entity = call(player, "getHandle"), level = call(world, "getHandle");
            Object portal = type("Blocks").getField("PORTAL").get(null);
            Object position = type("BlockPosition").getConstructor(int.class, int.class, int.class)
                    .newInstance(saved.x(), saved.y(), saved.z());
            Object pattern = type("BlockPortal").getMethod("c", type("World"), type("BlockPosition"))
                    .invoke(portal, level, position);
            Object relative = call(entity, "getPortalOffset");
            if (relative == null || call(entity, "getPortalDirection") == null) return false;
            Object facing = call(pattern, "getFacing"), along = call(facing, "e");
            Object corner = call(pattern, "a");
            boolean alongX = ((Enum<?>) call(facing, "k")).name().equals("X");
            double start = ((Number) call(corner, alongX ? "getZ" : "getX")).doubleValue();
            boolean negative = ((Enum<?>) call(along, "c")).name().equals("NEGATIVE");
            if (negative) start++;
            double relativeX = relative.getClass().getField("x").getDouble(relative);
            double relativeY = relative.getClass().getField("y").getDouble(relative);
            double across = start + (1 - relativeX) * ((Number) call(pattern, "d")).intValue() * (negative ? -1 : 1);
            double x = alongX ? saved.x() + .5 : across;
            double y = ((Number) call(corner, "getY")).doubleValue() + 1 - relativeY * ((Number) call(pattern, "e")).intValue();
            double z = alongX ? across : saved.z() + .5;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
            Object box = call(entity, "getBoundingBox");
            Location from = player.getLocation();
            Object translated = type("AxisAlignedBB").getMethod("d", double.class, double.class, double.class)
                    .invoke(box, x - from.getX(), y - from.getY(), z - from.getZ());
            Bounds bounds = new Bounds(translated);
            if (!world.getWorldBorder().isInside(new Location(world, bounds.minX, y, bounds.minZ))
                    || !world.getWorldBorder().isInside(new Location(world, Math.nextDown(bounds.maxX), y, Math.nextDown(bounds.maxZ)))) return false;
            return ((List<?>) type("World").getMethod("getCubes", type("Entity"), type("AxisAlignedBB"))
                    .invoke(level, entity, translated)).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    static final class Bounds {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        Bounds(Object nativeBox) throws ReflectiveOperationException {
            Class<?> type = nativeBox.getClass();
            minX = type.getField("a").getDouble(nativeBox); minY = type.getField("b").getDouble(nativeBox);
            minZ = type.getField("c").getDouble(nativeBox); maxX = type.getField("d").getDouble(nativeBox);
            maxY = type.getField("e").getDouble(nativeBox); maxZ = type.getField("f").getDouble(nativeBox);
        }
        double getMinX() { return minX; }
        double getMinY() { return minY; }
        double getMinZ() { return minZ; }
        double getMaxX() { return maxX; }
        double getMaxY() { return maxY; }
        double getMaxZ() { return maxZ; }
        boolean overlaps(double x1, double y1, double z1, double x2, double y2, double z2) {
            return minX < x2 && maxX > x1 && minY < y2 && maxY > y1 && minZ < z2 && maxZ > z1;
        }
    }
}
