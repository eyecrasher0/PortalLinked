// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Optional;
import java.util.OptionalInt;
import dev.eyecrasher.portallinked.PortalRef.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Bounded portal inspection on the owning server/region thread; no world-wide searches. */
final class Portals {
    private static final boolean PAPER_CONFIG = hasPaperConfig();
    private Portals() {}

    static Optional<Location> departure(Player player, Location contact) {
        return Optional.of(contact.clone());
    }

    static Optional<PortalRef> touching(Location feet) {
        World world = feet.getWorld();
        if (world == null) return Optional.empty();
        Optional<PortalRef> centered = at(feet.getBlock());
        if (centered.isPresent()) return centered;
        // Include the player's body at a portal edge, not just the feet block.
        int minX = floor(feet.getX() - 0.31), maxX = floor(feet.getX() + 0.31);
        int minZ = floor(feet.getZ() - 0.31), maxZ = floor(feet.getZ() + 0.31);
        for (int y = feet.getBlockY(); y <= floor(feet.getY() + 1.8); y++) {
            if (y < 0 || y >= world.getMaxHeight()) continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Optional<PortalRef> portal = at(world.getBlockAt(x, y, z));
                    if (portal.isPresent()) return portal;
                }
            }
        }
        return Optional.empty();
    }

    static Optional<PortalRef> touching(Player player) {
        Optional<PortalRef> centered = at(player.getLocation().getBlock());
        if (centered.isPresent()) return centered;
        LegacyGeometry.Bounds box = LegacyGeometry.bounds(player);
        if (box == null) return Optional.empty();
        World world = player.getWorld();
        for (int y = floor(box.getMinY()); y <= floor(Math.nextDown(box.getMaxY())); y++)
            for (int x = floor(box.getMinX()); x <= floor(Math.nextDown(box.getMaxX())); x++)
                for (int z = floor(box.getMinZ()); z <= floor(Math.nextDown(box.getMaxZ())); z++) {
                    Optional<PortalRef> portal = at(world.getBlockAt(x, y, z));
                    if (portal.isPresent()) return portal;
                }
        return Optional.empty();
    }

    static boolean intersects(PortalRef portal, Player player) {
        LegacyGeometry.Bounds box = LegacyGeometry.bounds(player);
        return box != null && portal.world().equals(player.getWorld().getUID())
                && box.overlaps(portal.x(), portal.y(), portal.z(),
                    portal.x() + (portal.axis() == Axis.X ? portal.width() : 1), portal.y() + portal.height(),
                    portal.z() + (portal.axis() == Axis.Z ? portal.width() : 1));
    }

    static Optional<PortalRef> at(Block touched) {
        if (touched.getType() != Material.PORTAL) return Optional.empty();
        Axis axis = touched.getData() == 1 ? Axis.X : touched.getData() == 2 ? Axis.Z : null;
        if (axis != Axis.X && axis != Axis.Z) return Optional.empty();
        World world = touched.getWorld();
        int dx = axis == Axis.X ? 1 : 0, dz = axis == Axis.Z ? 1 : 0;
        int x = touched.getX(), y = touched.getY(), z = touched.getZ();
        int steps = 0;
        while (portal(world, x, y - 1, z, axis)) {
            if (++steps >= 21) return Optional.empty();
            y--;
        }
        steps = 0;
        while (portal(world, x - dx, y, z - dz, axis)) {
            if (++steps >= 21) return Optional.empty();
            x -= dx;
            z -= dz;
        }
        int width = 0, height = 0;
        while (portal(world, x + dx * width, y, z + dz * width, axis)) {
            if (++width > 21) return Optional.empty();
        }
        while (portal(world, x, y + height, z, axis)) {
            if (++height > 21) return Optional.empty();
        }
        if (width < 2 || height < 3) return Optional.empty();
        for (int row = 0; row < height; row++) {
            if (!obsidian(world, x - dx, y + row, z - dz)
                    || !obsidian(world, x + dx * width, y + row, z + dz * width)) return Optional.empty();
            for (int col = 0; col < width; col++) {
                if (!portal(world, x + dx * col, y + row, z + dz * col, axis)) return Optional.empty();
            }
        }
        for (int col = 0; col < width; col++) {
            if (!obsidian(world, x + dx * col, y - 1, z + dz * col)
                    || !obsidian(world, x + dx * col, y + height, z + dz * col)) return Optional.empty();
        }
        return Optional.of(new PortalRef(world.getUID(), x, y, z, axis, width, height));
    }

    static boolean usable(PortalRef saved, World world) {
        if (!saved.world().equals(world.getUID()) || saved.y() <= 0
                || (long) saved.y() + saved.height() >= world.getMaxHeight()
                || Math.abs((long) saved.x()) > 29_999_984 || Math.abs((long) saved.z()) > 29_999_984)
            return false;
        Location origin = saved.searchOrigin(world);
        Location farCorner = origin.clone().add(saved.axis() == Axis.X ? saved.width() - 1 : 0,
                saved.height() - 1, saved.axis() == Axis.Z ? saved.width() - 1 : 0);
        return world.getWorldBorder().isInside(origin) && world.getWorldBorder().isInside(farCorner)
                && paperAllows(saved, world)
                && at(world.getBlockAt(saved.x(), saved.y(), saved.z())).filter(saved::equals).isPresent();
    }

    static boolean safeReturn(Player player, PortalRef source, PortalRef destination, World world) {
        return LegacyGeometry.safeReturn(player, destination, world);
    }

    private static boolean paperAllows(PortalRef saved, World world) {
        if (!PAPER_CONFIG || world.getEnvironment() != World.Environment.NETHER) return true;
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Object config = level.getClass().getField("paperConfig").get(level);
            boolean enabled = config.getClass().getField("netherVoidTopDamage").getBoolean(config);
            return !enabled || saved.y() < 128;
        } catch (ReflectiveOperationException ex) { return false; }
    }

    private static boolean hasPaperConfig() {
        try {
            Class.forName("com.destroystokyo.paper.PaperWorldConfig", false, Portals.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static boolean portal(World world, int x, int y, int z, Axis axis) {
        if (y < 0 || y >= world.getMaxHeight()) return false;
        Block block = world.getBlockAt(x, y, z);
        return block.getType() == Material.PORTAL
                && block.getData() == (axis == Axis.X ? 1 : 2);
    }

    private static boolean obsidian(World world, int x, int y, int z) {
        return y >= 0 && y < world.getMaxHeight()
                && world.getBlockAt(x, y, z).getType() == Material.OBSIDIAN;
    }

    private static int floor(double value) { return (int) Math.floor(value); }
}
