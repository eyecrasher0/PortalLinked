// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Objects;
import java.util.UUID;
import dev.eyecrasher.portallinked.PortalRef.Axis;
import org.bukkit.Location;
import org.bukkit.World;

/** An entire portal interior, independent of which block the player touched. */
final class PortalRef {
    enum Axis { X, Y, Z }
    private final UUID world;
    private final int x;
    private final int y;
    private final int z;
    private final Axis axis;
    private final int width;
    private final int height;

    public PortalRef(UUID world, int x, int y, int z, Axis axis, int width, int height) {
        Objects.requireNonNull(world);
        if (axis != Axis.X && axis != Axis.Z) throw new IllegalArgumentException("Portal axis");
        if (width < 2 || width > 21 || height < 3 || height > 21)
            throw new IllegalArgumentException("Portal size");

        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.axis = axis;
        this.width = width;
        this.height = height;
    }

    public UUID world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public Axis axis() { return axis; }
    public int width() { return width; }
    public int height() { return height; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PortalRef)) return false;
        PortalRef value = (PortalRef) other;
        return java.util.Objects.equals(world, value.world) && java.util.Objects.equals(x, value.x) && java.util.Objects.equals(y, value.y) && java.util.Objects.equals(z, value.z) && java.util.Objects.equals(axis, value.axis) && java.util.Objects.equals(width, value.width) && java.util.Objects.equals(height, value.height);
    }
    @Override public int hashCode() { return java.util.Objects.hash(world, x, y, z, axis, width, height); }
    @Override public String toString() { return "PortalRef[" + "world=" + world + ", " + "x=" + x + ", " + "y=" + y + ", " + "z=" + z + ", " + "axis=" + axis + ", " + "width=" + width + ", " + "height=" + height + "]"; }



    Location searchOrigin(World target) {
        if (!world.equals(target.getUID())) throw new IllegalArgumentException("Portal world");
        return new Location(target, x + 0.5, y, z + 0.5);
    }
}
