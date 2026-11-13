// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.NoSuchElementException;
import java.util.Optional;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public record PortalPair(NativePortal departure, NativePortal arrival) {
    public static final String KEY = "portallinked:last_crossing";

    public PortalPair {
        if (departure == null || arrival == null || departure.world().equals(arrival.world()))
            throw new IllegalArgumentException("Portal pair");
    }

    public Optional<NativePortal> returnPortal(NativePortal entered, String targetWorld) {
        return arrival.equals(entered) && departure.world().equals(targetWorld) ? Optional.of(departure) : Optional.empty();
    }

    public void write(ValueOutput output) {
        ValueOutput value = output.child(KEY);
        value.putInt("version", 1);
        departure.write(value.child("departure"));
        arrival.write(value.child("arrival"));
    }

    public static PortalPair read(ValueInput input) {
        try {
            ValueInput value = input.child(KEY).orElseThrow();
            if (value.getInt("version").orElseThrow() != 1) return null;
            return new PortalPair(NativePortal.read(value.child("departure").orElseThrow()),
                    NativePortal.read(value.child("arrival").orElseThrow()));
        } catch (IllegalArgumentException | NoSuchElementException ex) {
            return null;
        }
    }
}
