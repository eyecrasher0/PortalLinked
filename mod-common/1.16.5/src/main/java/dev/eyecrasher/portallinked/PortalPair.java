// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.NoSuchElementException;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class PortalPair {
    private final NativePortal departure;
    private final NativePortal arrival;

    public PortalPair(NativePortal departure, NativePortal arrival) {
        if (departure == null || arrival == null || departure.world().equals(arrival.world()))
            throw new IllegalArgumentException("Portal pair");

        this.departure = departure;
        this.arrival = arrival;
    }

    public NativePortal departure() { return departure; }
    public NativePortal arrival() { return arrival; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PortalPair)) return false;
        PortalPair value = (PortalPair) other;
        return java.util.Objects.equals(departure, value.departure) && java.util.Objects.equals(arrival, value.arrival);
    }
    @Override public int hashCode() { return java.util.Objects.hash(departure, arrival); }
    @Override public String toString() { return "PortalPair[" + "departure=" + departure + ", " + "arrival=" + arrival + "]"; }

    public static final String KEY = "portallinked:last_crossing";



    public Optional<NativePortal> returnPortal(NativePortal entered, String targetWorld) {
        return arrival.equals(entered) && departure.world().equals(targetWorld) ? Optional.of(departure) : Optional.empty();
    }

    public void write(CompoundTag output) {
        CompoundTag value = new CompoundTag();
        value.putInt("version", 1);
        CompoundTag departureTag = new CompoundTag(), arrivalTag = new CompoundTag();
        departure.write(departureTag);
        arrival.write(arrivalTag);
        value.put("departure", departureTag);
        value.put("arrival", arrivalTag);
        output.put(KEY, value);
    }

    public static PortalPair read(CompoundTag input) {
        try {
            if (!input.contains(KEY, 10)) return null;
            CompoundTag value = input.getCompound(KEY);
            if (!value.contains("version", 3) || value.getInt("version") != 1
                    || !value.contains("departure", 10)
                    || !value.contains("arrival", 10)) return null;
            return new PortalPair(NativePortal.read(value.getCompound("departure")),
                    NativePortal.read(value.getCompound("arrival")));
        } catch (IllegalArgumentException | NoSuchElementException ex) {
            return null;
        }
    }
}
