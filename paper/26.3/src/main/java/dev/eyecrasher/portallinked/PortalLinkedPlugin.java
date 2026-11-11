// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class PortalLinkedPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Contact> contacts = new HashMap<>();
    private final Map<UUID, Crossing> pending = new HashMap<>();
    private NamespacedKey linkKey;

    @Override public void onEnable() {
        linkKey = new NamespacedKey(this, "last_crossing");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Povratni put kroz Nether portale je uključen.");
    }

    @Override public void onDisable() {
        contacts.clear();
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void enter(EntityPortalEnterEvent event) {
        if (event.getPortalType() == PortalType.NETHER && event.getEntity() instanceof Player player)
            contacts.put(player.getUniqueId(), new Contact(event.getLocation(), Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void route(PlayerPortalEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || !normalNetherPair(event.getFrom().getWorld(), event.getTo().getWorld())) return;
        PortalRef source = source(event.getPlayer(), event.getFrom()).orElse(null);
        if (source == null) return;
        Crossing crossing = new Crossing(source, event);
        pending.put(id, crossing);
        // A failed or cancelled portal search must never become a later crossing.
        getServer().getScheduler().runTask(this, () -> pending.remove(id, crossing));

        World target = event.getTo().getWorld();
        Optional<PortalRef> saved = readLink(event.getPlayer())
                .flatMap(link -> link.returnPortal(source, target.getUID()));
        if (saved.isEmpty()) return;
        PortalRef destination = saved.get();
        if (!Portals.usable(destination, target)) return;

        // Paper still performs the portal transition, orientation, collision handling
        // and protection events. Only its search origin/radius change for a return.
        event.setTo(destination.searchOrigin(target));
        event.setSearchRadius(0);
        event.setCanCreatePortal(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void teleport(PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent) return;
        UUID id = event.getPlayer().getUniqueId();
        Crossing crossing = pending.get(id);
        if (crossing == null) return;
        if (event.isCancelled() || crossing.portal.isCancelled()
                || event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || !crossing.source.world().equals(event.getFrom().getWorld().getUID())
                || !normalNetherPair(event.getFrom().getWorld(), event.getTo().getWorld())) {
            pending.remove(id, crossing);
            return;
        }
        crossing.teleport = event;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void arrived(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Crossing crossing = pending.remove(player.getUniqueId());
        contacts.remove(player.getUniqueId());
        if (crossing == null || crossing.portal.isCancelled() || crossing.teleport == null
                || crossing.teleport.isCancelled() || !crossing.source.world().equals(event.getFrom().getUID())
                || !player.getWorld().equals(crossing.teleport.getTo().getWorld())
                || !normalNetherPair(event.getFrom(), player.getWorld())) return;
        // ChangedWorld is the success confirmation. PlayerPortalEvent#getTo is
        // only the pre-search position and is never used as an arrival portal.
        Portals.touching(player.getLocation()).ifPresent(destination -> {
            if (!Portals.usable(crossing.source, event.getFrom())) return;
            PortalLinked link = new PortalLinked(crossing.source, destination);
            player.getPersistentDataContainer().set(linkKey, PersistentDataType.BYTE_ARRAY, LinkCodec.encode(link));
        });
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        contacts.remove(event.getPlayer().getUniqueId());
        pending.remove(event.getPlayer().getUniqueId());
    }

    private Optional<PortalRef> source(Player player, Location from) {
        Contact contact = contacts.remove(player.getUniqueId());
        if (contact != null && Bukkit.getCurrentTick() - contact.tick <= 2
                && contact.block.getWorld().equals(from.getWorld())
                && contact.block.distanceSquared(from) < 9) {
            Optional<PortalRef> portal = Portals.at(contact.block.getBlock());
            if (portal.isPresent()) return portal;
        }
        return Portals.touching(from);
    }

    private Optional<PortalLinked> readLink(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(linkKey, PersistentDataType.BYTE_ARRAY);
        Optional<PortalLinked> link = LinkCodec.decode(bytes);
        if (bytes != null && link.isEmpty()) player.getPersistentDataContainer().remove(linkKey);
        return link;
    }

    private static boolean normalNetherPair(World from, World to) {
        if (from == null || to == null) return false;
        return (from.getEnvironment() == World.Environment.NORMAL && to.getEnvironment() == World.Environment.NETHER)
                || (from.getEnvironment() == World.Environment.NETHER && to.getEnvironment() == World.Environment.NORMAL);
    }

    private record Contact(Location block, int tick) {}

    private static final class Crossing {
        final PortalRef source;
        final PlayerPortalEvent portal;
        PlayerTeleportEvent teleport;

        Crossing(PortalRef source, PlayerPortalEvent portal) {
            this.source = source;
            this.portal = portal;
        }
    }
}
