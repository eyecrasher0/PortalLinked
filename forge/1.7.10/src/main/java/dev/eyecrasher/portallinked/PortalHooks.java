// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;

import net.minecraft.util.AxisAlignedBB;


import net.minecraft.world.WorldServer;
import net.minecraft.world.Teleporter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.LongHashMap;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PortalHooks {
    private static final Logger LOG = LogManager.getLogger("PortalLinked");
    private static final Map<UUID, Trip> PENDING = new HashMap<>();
    private static final Map<UUID, Contact> CONTACTS = new HashMap<>();
    private static Settings settings = new Settings(false, Collections.emptySet(), false);
    private static long generation;
    private static Path config;

    private PortalHooks() {}
    static void initialize(Path path) {
        config = path; settings = new Settings(false, Collections.emptySet(), false); generation++; PENDING.clear(); CONTACTS.clear();
        try {
            if (!Files.exists(config)) {
                Files.createDirectories(config.getParent());
                Files.write(config, ("{\n  \"enabled\": true,\n  \"excluded-worlds\": [],\n  \"debug\": false\n}\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ex) { LOG.warn("PortalLinked could not create its configuration; linking remains disabled."); }
        reload();
    }
    static boolean reload() {
        try (Reader input = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            Settings replacement = Settings.read(input);
            settings = replacement; generation++; PENDING.clear(); CONTACTS.clear();
            return true;
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            LOG.warn("PortalLinked configuration is invalid; previous settings are unchanged.");
            return false;
        }
    }
    static String status() {
        return "PortalLinked: enabled=" + settings.enabled + ", excluded-worlds=" + settings.excluded + ", debug=" + settings.debug + ".";
    }

    /** A 1.7 portal stores a boolean contact; preserve its first accepted block. */
    public static void portalContact(Entity entity,int x,int y,int z) {
        if (!(entity instanceof EntityPlayerMP) || !settings.enabled || entity.timeUntilPortal>0)return;
        EntityPlayerMP player=(EntityPlayerMP)entity;
        int tick=MinecraftServer.getServer().getTickCounter();
        Contact previous=CONTACTS.get(player.getUniqueID());
        if(previous!=null && previous.tick==tick)return;
        NativePortal portal=NativePortal.at(player.getServerForPlayer(),x,y,z);
        if(portal!=null)CONTACTS.put(player.getUniqueID(),new Contact(portal,tick));
    }
    public static void portalStarted(Entity entity,int targetDimension) {
        if(!(entity instanceof EntityPlayerMP))return;
        EntityPlayerMP player=(EntityPlayerMP)entity;
        PENDING.remove(player.getUniqueID());
        if(!settings.enabled || !((player.dimension==0 && targetDimension==-1)||(player.dimension==-1 && targetDimension==0)))return;
        Contact contact=CONTACTS.get(player.getUniqueID());
        int age=contact==null?2:MinecraftServer.getServer().getTickCounter()-contact.tick;
        if(contact==null || age<0 || age>1 || !contact.portal.usable(player.getServerForPlayer()))return;
        String target=targetDimension==0?"minecraft:overworld":"minecraft:the_nether";
        if(settings.allows(contact.portal) && !settings.excluded.contains(target))
            PENDING.put(player.getUniqueID(),new Trip(player,contact.portal,targetDimension,PortalPair.read(player),generation));
    }
    static void expireContacts() {
        int tick=MinecraftServer.getServer().getTickCounter();
        CONTACTS.entrySet().removeIf(entry->tick-entry.getValue().tick>1);
    }
    static void forgotten(UUID player) { PENDING.remove(player);CONTACTS.remove(player); }
    private static final class Contact {
        final NativePortal portal; final int tick;
        Contact(NativePortal portal,int tick){this.portal=portal;this.tick=tick;}
    }

    public static void portalFinished(Entity entity) { forgotten(entity.getUniqueID()); }
    static void stopped() { PENDING.clear(); CONTACTS.clear(); }

    /** Runs inside native placement, after every cancellable travel event. */
    public static boolean returnToSaved(Entity entity, WorldServer target, float yaw) {
        if (!(entity instanceof EntityPlayerMP)) return false;
        EntityPlayerMP player = (EntityPlayerMP) entity;
        Trip trip = PENDING.get(player.getUniqueID());
        if (trip == null || trip.player != player || trip.generation != generation
                || target.provider.dimensionId != trip.target) return false;
        long lookup = ChunkCoordIntPair.chunkXZ2Int(MathHelper.floor_double(player.posX),MathHelper.floor_double(player.posZ));
        trip.lookup = lookup;
        if (trip.placing || trip.pair == null || !trip.departure.equals(trip.pair.arrival)) return false;
        NativePortal saved = trip.pair.departure;
        if (!settings.allows(saved) || !saved.usable(target)) return false;
        if(trip.placing)return false;
        double x=player.posX,y=player.posY,z=player.posZ,mx=player.motionX,my=player.motionY,mz=player.motionZ;
        float originalYaw=player.rotationYaw,pitch=player.rotationPitch;
        boolean accepted=false;
        trip.placing=true;
        try {
            // A fresh native teleporter searches at the saved frame without
            // changing the world's shared portal cache or bypassing native exit
            // orientation. Its nested hook observes placing and uses vanilla.
            player.setLocationAndAngles(saved.x+.5,saved.y+.5,saved.z+.5,yaw,pitch);
            accepted=new Teleporter(target).placeInExistingPortal(player,x,y,z,yaw)
                    && saved.equals(trip.arrival)
                    && target.getCollidingBoundingBoxes(player,player.boundingBox).isEmpty();
        } finally {
            trip.placing=false;
            trip.lookup=lookup;
            trip.remembered=accepted;
            if(!accepted){
                trip.arrival=null;
                player.setLocationAndAngles(x,y,z,originalYaw,pitch);
                player.motionX=mx;player.motionY=my;player.motionZ=mz;
            }
        }
        return accepted;
    }

    /** The native cache identifies the chosen plane even when 1.7 exits outside it. */
    public static void portalPlaced(boolean success,Entity entity,WorldServer world,LongHashMap cache) {
        if(!success || !(entity instanceof EntityPlayerMP))return;
        Trip trip=PENDING.get(entity.getUniqueID());
        if(trip==null || trip.player!=entity || trip.generation!=generation || trip.remembered)return;
        Object selected=cache.getValueByKey(trip.lookup);
        if(selected instanceof ChunkCoordinates){
            ChunkCoordinates block=(ChunkCoordinates)selected;
            trip.arrival=NativePortal.at(world,block.posX,block.posY,block.posZ);
        }
    }

    static void changed(EntityPlayerMP player, int from, int to) {
        Trip trip = PENDING.get(player.getUniqueID());
        if (trip == null || trip.player != player || trip.generation != generation || to != trip.target
                || from == to || player.dimension != to || !settings.allows(trip.departure)) return;
        NativePortal arrival = trip.arrival;
        if (!settings.allows(arrival) || arrival.world.equals(trip.departure.world)) return;
        PortalPair.save(player, new PortalPair(trip.departure, arrival));
        if (settings.debug) LOG.info("PortalLinked remembered {} -> {} for {}.", trip.departure.world, arrival.world, player.getUniqueID());
    }

    private static final class Trip {
        boolean placing, remembered;
        long lookup;
        NativePortal arrival;
        final EntityPlayerMP player;
        final NativePortal departure;
        final int target;
        final PortalPair pair;
        final long generation;
        Trip(EntityPlayerMP player, NativePortal departure, int target, PortalPair pair, long generation) {
            this.player = player; this.departure = departure; this.target = target; this.pair = pair; this.generation = generation;
        }
    }
}
