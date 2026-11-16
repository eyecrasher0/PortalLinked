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
import net.minecraft.block.state.pattern.BlockPattern;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PortalHooks {
    private static final Logger LOG = LogManager.getLogger("PortalLinked");
    private static final Map<UUID, Trip> PENDING = new HashMap<>();
    private static Settings settings = new Settings(false, Collections.emptySet(), false);
    private static long generation;
    private static Path config;

    private PortalHooks() {}
    static void initialize(Path path) {
        config = path; settings = new Settings(false, Collections.emptySet(), false); generation++; PENDING.clear();
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
            settings = replacement; generation++; PENDING.clear();
            return true;
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            LOG.warn("PortalLinked configuration is invalid; previous settings are unchanged.");
            return false;
        }
    }
    static String status() {
        return "PortalLinked: enabled=" + settings.enabled + ", excluded-worlds=" + settings.excluded + ", debug=" + settings.debug + ".";
    }

    /** Called only at the native Nether-portal dimension call site, before Forge protection events. */
    public static void portalStarted(Entity entity, int targetDimension, BlockPos accepted) {
        if (!(entity instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) entity;
        PENDING.remove(player.getUniqueID());
        if (!settings.enabled || !((player.dimension == 0 && targetDimension == -1)
                || (player.dimension == -1 && targetDimension == 0))) return;
        NativePortal departure = NativePortal.at(player.getServerWorld(), accepted);
        String target = targetDimension == 0 ? "minecraft:overworld" : "minecraft:the_nether";
        if (settings.allows(departure) && !settings.excluded.contains(target))
            PENDING.put(player.getUniqueID(), new Trip(player, departure, targetDimension, PortalPair.read(player), generation));
    }

    public static void portalFinished(Entity entity) { PENDING.remove(entity.getUniqueID()); }
    static void stopped() { PENDING.clear(); }

    /** Runs inside native placement, after every cancellable travel event. */
    public static boolean returnToSaved(Entity entity, WorldServer target, float yaw) {
        if (!(entity instanceof EntityPlayerMP)) return false;
        EntityPlayerMP player = (EntityPlayerMP) entity;
        Trip trip = PENDING.get(player.getUniqueID());
        if (trip == null || trip.player != player || trip.generation != generation || trip.pair == null
                || target.provider.getDimension() != trip.target || !trip.departure.equals(trip.pair.arrival)) return false;
        NativePortal saved = trip.pair.departure;
        if (!settings.allows(saved) || !saved.usable(target)) return false;
        BlockPattern.PatternHelper pattern = Blocks.PORTAL.createPatternHelper(target, new BlockPos(saved.x, saved.y, saved.z));
        Vec3d relative = player.getLastPortalVec();
        EnumFacing facing = pattern.getForwards(), along = facing.rotateY(), entry = player.getTeleportDirection();
        if (relative == null || entry == null || !Double.isFinite(relative.x) || !Double.isFinite(relative.y)) return false;
        BlockPos corner = pattern.getFrontTopLeft();
        double start = facing.getAxis() == EnumFacing.Axis.X ? corner.getZ() : corner.getX();
        if (along.getAxisDirection() == EnumFacing.AxisDirection.NEGATIVE) start++;
        double across = start + (1 - relative.x) * pattern.getWidth() * along.getAxisDirection().getOffset();
        double x = facing.getAxis() == EnumFacing.Axis.X ? saved.x + .5 : across;
        double y = corner.getY() + 1 - relative.y * pattern.getHeight();
        double z = facing.getAxis() == EnumFacing.Axis.Z ? saved.z + .5 : across;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        AxisAlignedBB body = player.getEntityBoundingBox().offset(x - player.posX, y - player.posY, z - player.posZ);
        if (!target.getWorldBorder().contains(body) || !target.getCollisionBoxes(player, body).isEmpty()) return false;
        int turns = Math.floorMod(facing.getHorizontalIndex() - entry.getOpposite().getHorizontalIndex(), 4);
        double motionX = player.motionX, motionZ = player.motionZ;
        for (int turn = 0; turn < turns; turn++) { double previousX = motionX; motionX = -motionZ; motionZ = previousX; }
        player.motionX = motionX; player.motionZ = motionZ;
        player.setLocationAndAngles(x, y, z, yaw + turns * 90, player.rotationPitch);
        return true;
    }

    static void changed(EntityPlayerMP player, int from, int to) {
        Trip trip = PENDING.get(player.getUniqueID());
        if (trip == null || trip.player != player || trip.generation != generation || to != trip.target
                || from == to || player.dimension != to || !settings.allows(trip.departure)) return;
        NativePortal arrival = NativePortal.touching(player);
        if (!settings.allows(arrival) || arrival.world.equals(trip.departure.world)) return;
        PortalPair.save(player, new PortalPair(trip.departure, arrival));
        if (settings.debug) LOG.info("PortalLinked remembered {} -> {} for {}.", trip.departure.world, arrival.world, player.getUniqueID());
    }

    private static final class Trip {
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
