// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/** Identity of a complete portal interior, independent of the touched block. */
public record NativePortal(String world, int x, int y, int z, Direction.Axis axis, int width, int height) {
    public NativePortal {
        if (world == null || world.indexOf(':') <= 0 || Identifier.tryParse(world) == null)
            throw new IllegalArgumentException("Portal dimension");
        if ((axis != Direction.Axis.X && axis != Direction.Axis.Z) || width < 2 || width > 21
                || height < 3 || height > 21 || Math.abs((long) x) > 29_999_984
                || Math.abs((long) z) > 29_999_984) throw new IllegalArgumentException("Portal geometry");
    }

    public BlockUtil.FoundRectangle rectangle() { return new BlockUtil.FoundRectangle(new BlockPos(x, y, z), width, height); }

    public boolean usable(ServerLevel level) {
        if (!world.equals(level.dimension().identifier().toString()) || y <= level.getMinY()
                || (long) y + height > level.getMaxY()) return false;
        BlockPos first = new BlockPos(x, y, z);
        BlockPos last = first.offset(axis == Direction.Axis.X ? width - 1 : 0, height - 1,
                axis == Direction.Axis.Z ? width - 1 : 0);
        return level.getWorldBorder().isWithinBounds(first) && level.getWorldBorder().isWithinBounds(last)
                && at(level, first).filter(this::equals).isPresent();
    }

    public static Optional<NativePortal> touching(ServerPlayer player) {
        // The native transition position identifies its exit plane. A body can
        // overlap another parallel portal while its feet are inside this one.
        Optional<NativePortal> centered = at(player.level(), player.blockPosition());
        if (centered.isPresent()) return centered;
        AABB box = player.getBoundingBox();
        for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++)
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++)
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                    Optional<NativePortal> portal = at(player.level(), new BlockPos(x, y, z));
                    if (portal.isPresent()) return portal;
                }
        return Optional.empty();
    }

    public static Optional<NativePortal> at(ServerLevel level, BlockPos touched) {
        var state = level.getBlockState(touched);
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
        return Optional.of(new NativePortal(level.dimension().identifier().toString(), x, y, z, axis, width, height));
    }

    private static boolean portal(ServerLevel level, int x, int y, int z, Direction.Axis axis) {
        if (y < level.getMinY() || y > level.getMaxY()) return false;
        var state = level.getBlockState(new BlockPos(x, y, z));
        return state.is(Blocks.NETHER_PORTAL) && state.getValue(NetherPortalBlock.AXIS) == axis;
    }

    private static boolean obsidian(ServerLevel level, int x, int y, int z) {
        return y >= level.getMinY() && y <= level.getMaxY()
                && level.getBlockState(new BlockPos(x, y, z)).is(Blocks.OBSIDIAN);
    }

    public void write(ValueOutput output) {
        output.putString("world", world); output.putInt("x", x); output.putInt("y", y); output.putInt("z", z);
        output.putString("axis", axis.name()); output.putInt("width", width); output.putInt("height", height);
    }

    public static NativePortal read(ValueInput input) {
        return new NativePortal(input.getString("world").orElseThrow(), input.getInt("x").orElseThrow(),
                input.getInt("y").orElseThrow(), input.getInt("z").orElseThrow(),
                Direction.Axis.valueOf(input.getString("axis").orElseThrow()),
                input.getInt("width").orElseThrow(), input.getInt("height").orElseThrow());
    }
}
