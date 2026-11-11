// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Axis;

/** Fixed-size, versioned player-data payload. No location names or Java serialization. */
final class LinkCodec {
    private static final int VERSION = 1;
    private static final int PORTAL_BYTES = 16 + 12 + 3;
    private static final int SIZE = 1 + 2 * PORTAL_BYTES;

    private LinkCodec() {}

    static byte[] encode(PortalLinked link) {
        ByteBuffer out = ByteBuffer.allocate(SIZE).put((byte) VERSION);
        write(out, link.departure());
        write(out, link.arrival());
        return out.array();
    }

    static Optional<PortalLinked> decode(byte[] bytes) {
        if (bytes == null || bytes.length != SIZE) return Optional.empty();
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.get() != VERSION) return Optional.empty();
        try {
            return Optional.of(new PortalLinked(read(in), read(in)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static void write(ByteBuffer out, PortalRef portal) {
        out.putLong(portal.world().getMostSignificantBits()).putLong(portal.world().getLeastSignificantBits());
        out.putInt(portal.x()).putInt(portal.y()).putInt(portal.z());
        out.put((byte) (portal.axis() == Axis.X ? 0 : 1));
        out.put((byte) portal.width()).put((byte) portal.height());
    }

    private static PortalRef read(ByteBuffer in) {
        UUID world = new UUID(in.getLong(), in.getLong());
        int x = in.getInt(), y = in.getInt(), z = in.getInt();
        byte axis = in.get();
        if (axis != 0 && axis != 1) throw new IllegalArgumentException("Portal axis");
        return new PortalRef(world, x, y, z, axis == 0 ? Axis.X : Axis.Z,
                Byte.toUnsignedInt(in.get()), Byte.toUnsignedInt(in.get()));
    }
}
