// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.World;

/** An entire portal interior, independent of which block the player touched. */
record PortalRef(UUID world, int x, int y, int z, Axis axis, int width, int height) {
    PortalRef {
        Objects.requireNonNull(world);
        if (axis != Axis.X && axis != Axis.Z) throw new IllegalArgumentException("Portal axis");
        if (width < 2 || width > 21 || height < 3 || height > 21)
            throw new IllegalArgumentException("Portal size");
    }

    Location searchOrigin(World target) {
        if (!world.equals(target.getUID())) throw new IllegalArgumentException("Portal world");
        return new Location(target, x + 0.5, y, z + 0.5);
    }
}
