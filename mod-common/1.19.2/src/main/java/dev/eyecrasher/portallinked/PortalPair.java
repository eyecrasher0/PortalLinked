// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.NoSuchElementException;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public record PortalPair(NativePortal departure, NativePortal arrival) {
    public static final String KEY = "portallinked:last_crossing";

    public PortalPair {
        if (departure == null || arrival == null || departure.world().equals(arrival.world()))
            throw new IllegalArgumentException("Portal pair");
    }

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
            if (!input.contains(KEY, Tag.TAG_COMPOUND)) return null;
            CompoundTag value = input.getCompound(KEY);
            if (!value.contains("version", Tag.TAG_INT) || value.getInt("version") != 1
                    || !value.contains("departure", Tag.TAG_COMPOUND)
                    || !value.contains("arrival", Tag.TAG_COMPOUND)) return null;
            return new PortalPair(NativePortal.read(value.getCompound("departure")),
                    NativePortal.read(value.getCompound("arrival")));
        } catch (IllegalArgumentException | NoSuchElementException ex) {
            return null;
        }
    }
}
