// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

public final class PortalPair {
    public static final String KEY = "portallinked:last_crossing";
    public final NativePortal departure, arrival;

    public PortalPair(NativePortal departure, NativePortal arrival) {
        if (departure == null || arrival == null || departure.world.equals(arrival.world))
            throw new IllegalArgumentException("Portal pair");
        this.departure = departure; this.arrival = arrival;
    }

    public NBTTagCompound write() {
        NBTTagCompound value = new NBTTagCompound();
        value.setInteger("version", 1); value.setTag("departure", departure.write()); value.setTag("arrival", arrival.write());
        return value;
    }

    public static PortalPair read(EntityPlayer player) {
        NBTTagCompound value = stored(player);
        if (!value.hasKey("version", 3) || value.getInteger("version") != 1
                || !value.hasKey("departure", 10) || !value.hasKey("arrival", 10)) return null;
        try { return new PortalPair(NativePortal.read(value.getCompoundTag("departure")),
                NativePortal.read(value.getCompoundTag("arrival"))); }
        catch (IllegalArgumentException ex) { return null; }
    }

    public static NBTTagCompound stored(EntityPlayer player) {
        return player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getCompoundTag(KEY);
    }

    public static void save(EntityPlayer player, PortalPair pair) {
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (pair == null) persisted.removeTag(KEY); else persisted.setTag(KEY, pair.write());
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    public static void copy(EntityPlayer original, EntityPlayer replacement) {
        NBTTagCompound value = stored(original);
        if (value.hasNoTags()) return;
        NBTTagCompound data = replacement.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        persisted.setTag(KEY, value.copy());
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }
}
