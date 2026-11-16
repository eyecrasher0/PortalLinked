// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;

/** Identity of a complete portal interior, independent of the touched block. */
public final class NativePortal {
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final Direction.Axis axis;
    private final int width;
    private final int height;

    public NativePortal(String world, int x, int y, int z, Direction.Axis axis, int width, int height) {
        if (world == null || world.indexOf(':') <= 0 || world.endsWith(":") || ResourceLocation.tryParse(world) == null)
            throw new IllegalArgumentException("Portal dimension");
        if ((axis != Direction.Axis.X && axis != Direction.Axis.Z) || width < 2 || width > 21
                || height < 3 || height > 21 || Math.abs((long) x) > 29_999_984
                || Math.abs((long) z) > 29_999_984) throw new IllegalArgumentException("Portal geometry");

        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.axis = axis;
        this.width = width;
        this.height = height;
    }

    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public Direction.Axis axis() { return axis; }
    public int width() { return width; }
    public int height() { return height; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NativePortal)) return false;
        NativePortal value = (NativePortal) other;
        return java.util.Objects.equals(world, value.world) && java.util.Objects.equals(x, value.x) && java.util.Objects.equals(y, value.y) && java.util.Objects.equals(z, value.z) && java.util.Objects.equals(axis, value.axis) && java.util.Objects.equals(width, value.width) && java.util.Objects.equals(height, value.height);
    }
    @Override public int hashCode() { return java.util.Objects.hash(world, x, y, z, axis, width, height); }
    @Override public String toString() { return "NativePortal[" + "world=" + world + ", " + "x=" + x + ", " + "y=" + y + ", " + "z=" + z + ", " + "axis=" + axis + ", " + "width=" + width + ", " + "height=" + height + "]"; }



    public BlockUtil.FoundRectangle rectangle() { return new BlockUtil.FoundRectangle(new BlockPos(x, y, z), width, height); }

    public boolean usable(ServerLevel level) {
        if (!world.equals(level.dimension().location().toString()) || y <= 0
                || (long) y + height > (256 - 1)) return false;
        BlockPos first = new BlockPos(x, y, z);
        BlockPos last = first.offset(axis == Direction.Axis.X ? width - 1 : 0, height - 1,
                axis == Direction.Axis.Z ? width - 1 : 0);
        return level.getWorldBorder().isWithinBounds(first) && level.getWorldBorder().isWithinBounds(last)
                && at(level, first).filter(this::equals).isPresent();
    }

    public static Optional<NativePortal> touching(ServerPlayer player) {
        // The native transition position identifies its exit plane. A body can
        // overlap another parallel portal while its feet are inside this one.
        Optional<NativePortal> centered = at(player.getLevel(), player.blockPosition());
        if (centered.isPresent()) return centered;
        AABB box = player.getBoundingBox();
        for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++)
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++)
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                    Optional<NativePortal> portal = at(player.getLevel(), new BlockPos(x, y, z));
                    if (portal.isPresent()) return portal;
                }
        return Optional.empty();
    }

    public static Optional<NativePortal> at(ServerLevel level, BlockPos touched) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(touched);
        if (!state.is(Blocks.NETHER_PORTAL)) return Optional.empty();
        Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
        int dx = axis == Direction.Axis.X ? 1 : 0, dz = axis == Direction.Axis.Z ? 1 : 0;
        int x = touched.getX(), y = touched.getY(), z = touched.getZ(), steps = 0;
        while (portal(level, x, y - 1, z, axis)) { if (++steps >= 21) return Optional.empty(); y--; }
        steps = 0;
        while (portal(level, x - dx, y, z - dz, axis)) {
            if (++steps >= 21) return Optional.empty(); x -= dx; z -= dz;
        }
        int width = 0, height = 0;
        while (portal(level, x + dx * width, y, z + dz * width, axis))
            if (++width > 21) return Optional.empty();
        while (portal(level, x, y + height, z, axis)) if (++height > 21) return Optional.empty();
        if (width < 2 || height < 3) return Optional.empty();
        for (int row = 0; row < height; row++) {
            if (!obsidian(level, x - dx, y + row, z - dz)
                    || !obsidian(level, x + dx * width, y + row, z + dz * width)) return Optional.empty();
            for (int col = 0; col < width; col++)
                if (!portal(level, x + dx * col, y + row, z + dz * col, axis)) return Optional.empty();
        }
        for (int col = 0; col < width; col++)
            if (!obsidian(level, x + dx * col, y - 1, z + dz * col)
                    || !obsidian(level, x + dx * col, y + height, z + dz * col)) return Optional.empty();
        return Optional.of(new NativePortal(level.dimension().location().toString(), x, y, z, axis, width, height));
    }

    private static boolean portal(ServerLevel level, int x, int y, int z, Direction.Axis axis) {
        if (y < 0 || y > (256 - 1)) return false;
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return state.is(Blocks.NETHER_PORTAL) && state.getValue(NetherPortalBlock.AXIS) == axis;
    }

    private static boolean obsidian(ServerLevel level, int x, int y, int z) {
        return y >= 0 && y <= (256 - 1)
                && level.getBlockState(new BlockPos(x, y, z)).is(Blocks.OBSIDIAN);
    }

    public void write(CompoundTag output) {
        output.putString("world", world); output.putInt("x", x); output.putInt("y", y); output.putInt("z", z);
        output.putString("axis", axis.name()); output.putInt("width", width); output.putInt("height", height);
    }

    public static NativePortal read(CompoundTag input) {
        if (!input.contains("world", 8) || !input.contains("axis", 8))
            throw new IllegalArgumentException("Portal text fields");
        for (String field : new String[]{"x", "y", "z", "width", "height"})
            if (!input.contains(field, 3)) throw new IllegalArgumentException("Portal geometry field " + field);
        return new NativePortal(input.getString("world"), input.getInt("x"), input.getInt("y"), input.getInt("z"),
                Direction.Axis.valueOf(input.getString("axis")), input.getInt("width"), input.getInt("height"));
    }
}
