// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

/** Folia's native async portal path does not emit PlayerPortalEvent. */
final class FoliaRoutes implements Listener {
    private final PortalLinkedPlugin plugin;
    private final Map<UUID, Trip> trips = new ConcurrentHashMap<>();
    private final Map<UUID, PortalRef> vanillaFallback = new ConcurrentHashMap<>();

    FoliaRoutes(PortalLinkedPlugin plugin) { this.plugin = plugin; }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) { return false; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void enter(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getPortalType() != PortalType.NETHER) return;
        PortalLinkedPlugin.Settings active = plugin.settingsSnapshot();
        Trip ongoing = trips.get(player.getUniqueId());
        if (ongoing != null && ongoing.returning && current(player, ongoing)) {
            if (Portals.intersects(ongoing.source, player)) {
                plugin.withSettings(ongoing.settings, () -> {
                    if (!current(player, ongoing)) return;
                    ongoing.contact = event;
                    if (!FoliaNative.hold(player, event.getLocation())) trips.remove(player.getUniqueId(), ongoing);
                });
                return;
            }
            trips.remove(player.getUniqueId(), ongoing);
        }
        if (player.getPortalCooldown() > 0) return;
        int cooldown = readyCooldown(player);
        if (cooldown < 0) return;
        World from = player.getWorld();
        World defaultTarget = Bukkit.getWorlds().stream().filter(world -> world.getEnvironment()
                == (from.getEnvironment() == World.Environment.NETHER ? World.Environment.NORMAL : World.Environment.NETHER))
                .findFirst().orElse(null);
        if (defaultTarget == null) return;
        Location contact = event.getLocation();
        PortalRef source = Bukkit.isOwnedByCurrentRegion(contact, 2)
                ? Portals.at(contact.getBlock()).orElse(null) : null;
        if (source == null || !Portals.usable(source, from)) return;
        if (ongoing != null && ongoing.source.equals(source) && ongoing.settings == active && current(player, ongoing)) return;
        PortalLinked link = plugin.readLink(player).orElse(null);
        PortalRef remembered = link != null && link.arrival().equals(source) ? link.departure() : null;
        World rememberedWorld = remembered == null ? null : Bukkit.getWorld(remembered.world());
        World target = rememberedWorld != null && plugin.activePair(active, from, rememberedWorld) ? rememberedWorld : defaultTarget;
        if (!plugin.activePair(active, from, target) || !nativeAllows(player, target)) return;
        PortalRef saved = remembered != null && remembered.world().equals(target.getUID()) ? remembered : null;
        boolean bypass = source.equals(vanillaFallback.remove(player.getUniqueId()));
        boolean returning = saved != null && !bypass && !player.isInsideVehicle() && player.getPassengers().isEmpty();
        Trip trip = new Trip(source, target.getUID(), active, player.getTicksLived(), returning, event, cooldown);
        if (!plugin.withSettings(active, () -> {
            trips.put(player.getUniqueId(), trip);
            if (returning && !FoliaNative.hold(player, contact)) trips.remove(player.getUniqueId(), trip);
        })) return;
        if (trips.get(player.getUniqueId()) != trip) return;
        if (!returning) {
            watch(player, trip, 0);
            return;
        }
        Location fromPosition = player.getLocation();
        loadPortal(saved, target, player.getWidth(), player.getHeight()).whenComplete((usable, error) -> player.getScheduler().run(plugin, task -> {
            if (!current(player, trip)) return;
            if (error != null || !Boolean.TRUE.equals(usable)) {
                plugin.withSettings(trip.settings, () -> {
                    if (!current(player, trip)) return;
                    trips.remove(player.getUniqueId(), trip);
                    vanillaFallback.put(player.getUniqueId(), source);
                });
                plugin.debug("Saved return unavailable for " + player.getName() + "; using vanilla lookup.");
                return;
            }
            if (!Portals.intersects(source, player)) { trips.remove(player.getUniqueId(), trip); return; }
            Location destination = saved.searchOrigin(target);
            destination.add(saved.axis() == Axis.X ? (saved.width() - 1) / 2.0 : 0, 0,
                    saved.axis() == Axis.Z ? (saved.width() - 1) / 2.0 : 0);
            destination.setYaw(fromPosition.getYaw() + (source.axis() == saved.axis() ? 0 : 90));
            destination.setPitch(fromPosition.getPitch());
            PlayerPortalEvent portal = new PlayerPortalEvent(player, player.getLocation(), destination,
                    PlayerTeleportEvent.TeleportCause.NETHER_PORTAL, 0, false, 0);
            Bukkit.getPluginManager().callEvent(portal);
            if (portal.isCancelled() || portal.getTo() == null || !current(player, trip)) {
                finishCancelled(player, trip); return;
            }
            PlayerTeleportEvent teleport = new PlayerTeleportEvent(player, player.getLocation(), portal.getTo(),
                    PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
            Bukkit.getPluginManager().callEvent(teleport);
            if (teleport.isCancelled() || teleport.getTo() == null || !current(player, trip)) {
                finishCancelled(player, trip); return;
            }
            Location finalDestination = teleport.getTo().clone();
            if (!plugin.activePair(trip.settings, from, finalDestination.getWorld()) || !nativeAllows(player, finalDestination.getWorld())) {
                finishCancelled(player, trip); return;
            }
            plugin.withSettings(trip.settings, () -> {
                if (!current(player, trip)) return;
                trip.target = finalDestination.getWorld().getUID();
                player.setPortalCooldown(trip.cooldown);
                plugin.debug("Returning " + player.getName() + " to their saved portal.");
                if (!FoliaNative.teleport(player, finalDestination, () -> record(player, trip)))
                    trips.remove(player.getUniqueId(), trip);
            });
        }, () -> trips.remove(player.getUniqueId(), trip)));
    }

    private void finishCancelled(Player player, Trip trip) {
        plugin.withSettings(trip.settings, () -> { if (current(player, trip)) player.setPortalCooldown(trip.cooldown); });
        trips.remove(player.getUniqueId(), trip);
    }

    /** Tracking starts on the destination owner before movement packets can run. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void arrived(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Trip trip = trips.get(player.getUniqueId());
        if (trip == null || !current(player, trip) || !event.getWorld().getUID().equals(trip.target)) return;
        PortalRef arrival = localPortal(player);
        if (arrival == null || !Portals.usable(arrival, event.getWorld())) return;
        trip.arrival = arrival;
        if (!trip.returning) record(player, trip);
    }

    private void watch(Player player, Trip trip, int attempts) {
        player.getScheduler().runDelayed(plugin, task -> {
            if (!current(player, trip)) return;
            if (!player.getWorld().getUID().equals(trip.source.world())) {
                // Arrival is captured by EntityAddToWorldEvent, never by a late feet sample.
                if (trip.arrival == null) trips.remove(player.getUniqueId(), trip);
            } else if (attempts < 200 && player.getTicksLived() - trip.tick <= 200) watch(player, trip, attempts + 1);
            else trips.remove(player.getUniqueId(), trip);
        }, () -> trips.remove(player.getUniqueId(), trip), 1);
    }

    private void record(Player player, Trip trip) {
        World from = Bukkit.getWorld(trip.source.world());
        if (from == null || !player.getWorld().getUID().equals(trip.target) || !plugin.activePair(from, player.getWorld())) {
            trips.remove(player.getUniqueId(), trip); return;
        }
        PortalRef arrival = trip.arrival;
        if (arrival == null) { trips.remove(player.getUniqueId(), trip); return; }
        // Source was validated at entry, arrival at registration. Commit this
        // confirmed crossing before the next logout/save, without a remote wait.
        plugin.withSettings(trip.settings, () -> {
            if (current(player, trip) && player.getWorld().getUID().equals(arrival.world())
                    && plugin.activePair(trip.settings, from, player.getWorld()))
                plugin.saveLink(player, trip.source, arrival);
        });
        trips.remove(player.getUniqueId(), trip);
    }

    private boolean current(Player player, Trip trip) {
        if (!plugin.isEnabled() || !player.isOnline() || trips.get(player.getUniqueId()) != trip
                || trip.settings != plugin.settingsSnapshot()) return false;
        // Inspect the exact contact after dispatch: even a later MONITOR listener
        // may cancel it and independently teleport the player to another portal.
        if (trip.contact.isCancelled()) {
            trips.remove(player.getUniqueId(), trip);
            return false;
        }
        return true;
    }

    private PortalRef localPortal(Player player) {
        BoundingBox box = player.getBoundingBox();
        if (!Bukkit.isOwnedByCurrentRegion(player.getWorld(), (int) Math.floor(box.getMinX()) >> 4,
                (int) Math.floor(box.getMinZ()) >> 4, (int) Math.floor(box.getMaxX()) >> 4,
                (int) Math.floor(box.getMaxZ()) >> 4)) return null;
        return Portals.touching(player).orElse(null);
    }

    private CompletableFuture<Boolean> loadPortal(PortalRef portal, World world, double width, double height) {
        if (!portal.world().equals(world.getUID()) || portal.y() <= world.getMinHeight()
                || (long) portal.y() + portal.height() >= world.getMaxHeight()
                || Math.abs((long) portal.x()) > 29_999_984 || Math.abs((long) portal.z()) > 29_999_984)
            return CompletableFuture.completedFuture(false);
        List<CompletableFuture<?>> chunks = new ArrayList<>();
        int minX = (portal.x() - 1) >> 4, minZ = (portal.z() - 1) >> 4;
        int maxX = (portal.x() + (portal.axis() == Axis.X ? portal.width() : 1)) >> 4;
        int maxZ = (portal.z() + (portal.axis() == Axis.Z ? portal.width() : 1)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) chunks.add(world.getChunkAtAsync(x, z, true));
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Bukkit.getRegionScheduler().execute(plugin, world, portal.x() >> 4, portal.z() >> 4, () -> {
                try {
                    if (!Bukkit.isOwnedByCurrentRegion(world, minX, minZ, maxX, maxZ)) { result.complete(false); return; }
                    Location point = portal.searchOrigin(world).add(portal.axis() == Axis.X ? (portal.width() - 1) / 2.0 : 0,
                            0, portal.axis() == Axis.Z ? (portal.width() - 1) / 2.0 : 0);
                    boolean safe = width == 0 || !world.hasCollisionsIn(new BoundingBox(point.getX() - width / 2, point.getY(),
                            point.getZ() - width / 2, point.getX() + width / 2, point.getY() + height, point.getZ() + width / 2));
                    result.complete(safe && Portals.usable(portal, world));
                } catch (RuntimeException ex) { result.completeExceptionally(ex); }
            });
            return result;
        }).orTimeout(10, TimeUnit.SECONDS);
    }

    /** Read the native progress and delay instead of inventing a second portal timer. */
    private int readyCooldown(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            Field processField = entityClass.getDeclaredField("portalProcess"); processField.setAccessible(true);
            Object process = processField.get(handle);
            int elapsed = process == null ? 0 : (int) process.getClass().getMethod("getPortalTime").invoke(process);
            Object level = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object block = Class.forName("net.minecraft.world.level.block.Blocks").getField("NETHER_PORTAL").get(null);
            int delay = (int) block.getClass().getMethod("getPortalTransitionTime",
                    Class.forName("net.minecraft.server.level.ServerLevel"), entityClass).invoke(block, level, handle);
            return elapsed >= delay ? (int) handle.getClass().getMethod("getDimensionChangingDelay").invoke(handle) : -1;
        } catch (ReflectiveOperationException ex) { return -1; }
    }

    private boolean nativeAllows(Player player, World target) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object source = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object destination = target.getClass().getMethod("getHandle").invoke(target);
            return (boolean) source.getClass().getMethod("isAllowedToEnterPortal", Class.forName("net.minecraft.world.level.Level"))
                    .invoke(source, destination)
                    && (boolean) handle.getClass().getMethod("canPortalAsync", Class.forName("net.minecraft.server.level.ServerLevel"), boolean.class)
                    .invoke(handle, destination, true);
        } catch (ReflectiveOperationException ex) { return false; }
    }

    void forget(CommandSender sender, Player target) {
        target.getScheduler().run(plugin, task -> {
            plugin.forget(target);
            reply(sender, "Forgot portal link for " + target.getName() + ".");
        }, () -> reply(sender, "Player disconnected before their portal link could be cleared."));
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) player.getScheduler().run(plugin, task -> player.sendMessage(message), null);
        else sender.sendMessage(message);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }
    void forget(UUID id) { trips.remove(id); vanillaFallback.remove(id); }
    void clear() { trips.clear(); vanillaFallback.clear(); }

    private static final class Trip {
        final PortalRef source;
        UUID target;
        final PortalLinkedPlugin.Settings settings;
        final int tick;
        EntityPortalEnterEvent contact;
        final int cooldown;
        final boolean returning;
        PortalRef arrival;
        Trip(PortalRef source, UUID target, PortalLinkedPlugin.Settings settings, int tick, boolean returning, EntityPortalEnterEvent contact, int cooldown) {
            this.source = source; this.target = target; this.settings = settings; this.tick = tick; this.returning = returning;
            this.contact = contact;
            this.cooldown = cooldown;
        }
    }
}
