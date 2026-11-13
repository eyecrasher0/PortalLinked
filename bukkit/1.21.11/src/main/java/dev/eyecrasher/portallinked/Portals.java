// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Optional;
import java.util.OptionalInt;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** Bounded portal inspection on the owning server/region thread; no world-wide searches. */
final class Portals {
    private static final boolean PAPER_CONFIG = hasPaperConfig();
    private Portals() {}

    static Optional<Location> departure(Player player, Location contact) {
        try {
            Object entity = player.getClass().getMethod("getHandle").invoke(player);
            var field = Class.forName("net.minecraft.world.entity.Entity").getDeclaredField("portalProcess");
            field.setAccessible(true);
            Object process = field.get(entity);
            Object block = Class.forName("net.minecraft.world.level.block.Blocks").getField("NETHER_PORTAL").get(null);
            if (process == null || !(boolean) process.getClass().getMethod("isSamePortal",
                    Class.forName("net.minecraft.world.level.block.Portal")).invoke(process, block)
                    || !(boolean) process.getClass().getMethod("isInsidePortalThisTick").invoke(process))
                return Optional.of(contact.clone());
            Object entry = process.getClass().getMethod("getEntryPosition").invoke(process);
            return Optional.of(new Location(contact.getWorld(),
                    (int) entry.getClass().getMethod("getX").invoke(entry),
                    (int) entry.getClass().getMethod("getY").invoke(entry),
                    (int) entry.getClass().getMethod("getZ").invoke(entry)));
        } catch (ReflectiveOperationException ex) { return Optional.empty(); }
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
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
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
        BoundingBox box = player.getBoundingBox();
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
        return portal.world().equals(player.getWorld().getUID()) && player.getBoundingBox().overlaps(new BoundingBox(
                portal.x(), portal.y(), portal.z(), portal.x() + (portal.axis() == Axis.X ? portal.width() : 1),
                portal.y() + portal.height(), portal.z() + (portal.axis() == Axis.Z ? portal.width() : 1)));
    }

    static Optional<PortalRef> at(Block touched) {
        if (touched.getType() != Material.NETHER_PORTAL
                || !(touched.getBlockData() instanceof Orientable data)) return Optional.empty();
        Axis axis = data.getAxis();
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
        if (!saved.world().equals(world.getUID()) || saved.y() <= world.getMinHeight()
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
        try {
            Object entity = player.getClass().getMethod("getHandle").invoke(player);
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> levelType = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> positionType = Class.forName("net.minecraft.core.BlockPos");
            Class<?> rectangleType = Class.forName("net.minecraft.util.BlockUtil$FoundRectangle");
            Class<?> transitionType = Class.forName("net.minecraft.world.level.portal.TeleportTransition");
            Class<?> afterType = Class.forName("net.minecraft.world.level.portal.TeleportTransition$PostTeleportTransition");
            Class<?> vectorType = Class.forName("net.minecraft.world.phys.Vec3");
            Class<?> boxType = Class.forName("net.minecraft.world.phys.AABB");
            var position = positionType.getConstructor(int.class, int.class, int.class);
            Object entry = position.newInstance(source.x(), source.y(), source.z());
            Object exit = rectangleType.getConstructor(positionType, int.class, int.class).newInstance(
                    position.newInstance(destination.x(), destination.y(), destination.z()), destination.width(), destination.height());
            var build = Class.forName("net.minecraft.world.level.block.NetherPortalBlock").getDeclaredMethod(
                    "getDimensionTransitionFromExit", entityType, positionType, rectangleType, levelType, afterType);
            build.setAccessible(true);
            Object transition = build.invoke(null, entity, entry, exit, level, transitionType.getField("DO_NOTHING").get(null));
            Object arrival = transitionType.getMethod("position").invoke(transition);
            Object offset = vectorType.getMethod("subtract", vectorType).invoke(arrival, entityType.getMethod("position").invoke(entity));
            Object bounds = boxType.getMethod("move", vectorType).invoke(entityType.getMethod("getBoundingBox").invoke(entity), offset);
            return (boolean) levelType.getMethod("noCollision", entityType, boxType).invoke(level, entity, bounds);
        } catch (ReflectiveOperationException ex) { return false; }
    }

    private static boolean paperAllows(PortalRef saved, World world) {
        if (!PAPER_CONFIG) return true;
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Object nether = Class.forName("net.minecraft.world.level.dimension.LevelStem").getField("NETHER").get(null);
            if (!nether.equals(level.getClass().getMethod("getTypeKey").invoke(level))) return true;
            Object config = level.getClass().getMethod("paperConfig").invoke(level);
            Object environment = config.getClass().getField("environment").get(config);
            Object ceiling = environment.getClass().getField("netherCeilingVoidDamageHeight").get(environment);
            OptionalInt height = (OptionalInt) ceiling.getClass().getMethod("value").invoke(ceiling);
            return height.isEmpty() || saved.y() < height.getAsInt();
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private static boolean hasPaperConfig() {
        try {
            Class.forName("io.papermc.paper.configuration.WorldConfiguration", false, Portals.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static boolean portal(World world, int x, int y, int z, Axis axis) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return false;
        Block block = world.getBlockAt(x, y, z);
        return block.getType() == Material.NETHER_PORTAL
                && block.getBlockData() instanceof Orientable data && data.getAxis() == axis;
    }

    private static boolean obsidian(World world, int x, int y, int z) {
        return y >= world.getMinHeight() && y < world.getMaxHeight()
                && world.getBlockAt(x, y, z).getType() == Material.OBSIDIAN;
    }

    private static int floor(double value) { return (int) Math.floor(value); }
}
