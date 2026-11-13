// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Objects;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

/** The full framed interior, with the same persisted geometry as the modern adapters. */
public final class NativePortal {
    public final String world;
    public final int x, y, z, width, height;
    public final EnumFacing.Axis axis;

    public NativePortal(String world, int x, int y, int z, EnumFacing.Axis axis, int width, int height) {
        if (!world.equals("minecraft:overworld") && !world.equals("minecraft:the_nether"))
            throw new IllegalArgumentException("Portal dimension");
        if ((axis != EnumFacing.Axis.X && axis != EnumFacing.Axis.Z) || width < 2 || width > 21
                || height < 3 || height > 21 || Math.abs((long) x) > 29_999_984
                || Math.abs((long) z) > 29_999_984) throw new IllegalArgumentException("Portal geometry");
        this.world = world; this.x = x; this.y = y; this.z = z;
        this.axis = axis; this.width = width; this.height = height;
    }

    public static String world(WorldServer level) {
        int dimension = level.provider.getDimension();
        return dimension == 0 ? "minecraft:overworld" : dimension == -1 ? "minecraft:the_nether" : "";
    }

    public static NativePortal touching(EntityPlayerMP player) {
        WorldServer level = player.getServerWorld();
        NativePortal centered = at(level, player.getPosition());
        if (centered != null) return centered;
        AxisAlignedBB box = player.getEntityBoundingBox();
        for (int y = MathHelper.floor(box.minY); y <= MathHelper.floor(box.maxY); y++)
            for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX); x++)
                for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ); z++) {
                    NativePortal found = at(level, new BlockPos(x, y, z));
                    if (found != null) return found;
                }
        return null;
    }

    public static NativePortal at(WorldServer level, BlockPos touched) {
        if (touched == null || world(level).isEmpty()) return null;
        IBlockState state = level.getBlockState(touched);
        if (state.getBlock() != Blocks.PORTAL) return null;
        EnumFacing.Axis axis = state.getValue(BlockPortal.AXIS);
        int dx = axis == EnumFacing.Axis.X ? 1 : 0, dz = axis == EnumFacing.Axis.Z ? 1 : 0;
        int x = touched.getX(), y = touched.getY(), z = touched.getZ(), steps = 0;
        while (portal(level, x, y - 1, z, axis)) { if (++steps >= 21) return null; y--; }
        steps = 0;
        while (portal(level, x - dx, y, z - dz, axis)) { if (++steps >= 21) return null; x -= dx; z -= dz; }
        int width = 0, height = 0;
        while (portal(level, x + dx * width, y, z + dz * width, axis)) if (++width > 21) return null;
        while (portal(level, x, y + height, z, axis)) if (++height > 21) return null;
        if (width < 2 || height < 3) return null;
        for (int row = 0; row < height; row++) {
            if (!obsidian(level, x - dx, y + row, z - dz)
                    || !obsidian(level, x + dx * width, y + row, z + dz * width)) return null;
            for (int col = 0; col < width; col++)
                if (!portal(level, x + dx * col, y + row, z + dz * col, axis)) return null;
        }
        for (int col = 0; col < width; col++)
            if (!obsidian(level, x + dx * col, y - 1, z + dz * col)
                    || !obsidian(level, x + dx * col, y + height, z + dz * col)) return null;
        return new NativePortal(world(level), x, y, z, axis, width, height);
    }

    public boolean usable(WorldServer level) {
        if (!world.equals(world(level)) || y <= 0 || (long) y + height >= level.getHeight()) return false;
        BlockPos first = new BlockPos(x, y, z);
        BlockPos last = first.add(axis == EnumFacing.Axis.X ? width - 1 : 0, height - 1,
                axis == EnumFacing.Axis.Z ? width - 1 : 0);
        return level.getWorldBorder().contains(first) && level.getWorldBorder().contains(last)
                && equals(at(level, first));
    }

    private static boolean portal(WorldServer level, int x, int y, int z, EnumFacing.Axis axis) {
        if (y < 0 || y >= level.getHeight()) return false;
        IBlockState block = level.getBlockState(new BlockPos(x, y, z));
        return block.getBlock() == Blocks.PORTAL && block.getValue(BlockPortal.AXIS) == axis;
    }
    private static boolean obsidian(WorldServer level, int x, int y, int z) {
        return y >= 0 && y < level.getHeight() && level.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.OBSIDIAN;
    }

    public NBTTagCompound write() {
        NBTTagCompound value = new NBTTagCompound();
        value.setString("world", world); value.setInteger("x", x); value.setInteger("y", y); value.setInteger("z", z);
        value.setString("axis", axis.name()); value.setInteger("width", width); value.setInteger("height", height);
        return value;
    }
    public static NativePortal read(NBTTagCompound value) {
        for (String key : new String[] {"x", "y", "z", "width", "height"})
            if (!value.hasKey(key, 3)) throw new IllegalArgumentException("Missing integer geometry");
        if (!value.hasKey("world", 8) || !value.hasKey("axis", 8)) throw new IllegalArgumentException("Missing portal identity");
        return new NativePortal(value.getString("world"), value.getInteger("x"), value.getInteger("y"),
                value.getInteger("z"), EnumFacing.Axis.valueOf(value.getString("axis")),
                value.getInteger("width"), value.getInteger("height"));
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof NativePortal)) return false;
        NativePortal p = (NativePortal) other;
        return world.equals(p.world) && x == p.x && y == p.y && z == p.z && axis == p.axis && width == p.width && height == p.height;
    }
    @Override public int hashCode() { return Objects.hash(world, x, y, z, axis, width, height); }
}
