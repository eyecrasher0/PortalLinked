// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private final Map<UUID, Contact> contacts = new ConcurrentHashMap<>();
    private final Map<UUID, Crossing> pending = new ConcurrentHashMap<>();
    private NamespacedKey linkKey;
    private volatile Settings settings = new Settings(false, Set.of(), false, 0);
    private FoliaRoutes folia;

    @Override public void onEnable() {
        linkKey = new NamespacedKey(this, "last_crossing");
        saveDefaultConfig();
        if (!loadSettings()) getLogger().warning("Portal linking remains disabled until the configuration is fixed and reloaded.");
        if (FoliaRoutes.isFolia()) {
            folia = new FoliaRoutes(this);
            getServer().getPluginManager().registerEvents(folia, this);
        } else getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PortalLinked enabled.");
    }

    @Override public void onDisable() {
        contacts.clear();
        pending.clear();
        if (folia != null) folia.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void enter(EntityPortalEnterEvent event) {
        if (settings.enabled && !settings.excluded.contains(event.getLocation().getWorld().getName())
                && event.getLocation().getBlock().getType() == Material.NETHER_PORTAL
                && event.getEntity() instanceof Player player)
            contacts.put(player.getUniqueId(), new Contact(event.getLocation().clone(), player.getTicksLived()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void route(PlayerPortalEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL || event.getTo() == null
                || !activePair(event.getFrom().getWorld(), event.getTo().getWorld())) return;
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
        if (!Portals.usable(destination, target)) {
            debug("Saved return unavailable for " + event.getPlayer().getName() + "; using vanilla lookup.");
            return;
        }

        // The server still handles orientation, collisions and protection events.
        // A remembered return must not create a replacement portal.
        event.setTo(destination.searchOrigin(target));
        event.setSearchRadius(0);
        event.setCanCreatePortal(false);
        debug("Returning " + event.getPlayer().getName() + " to their saved portal.");
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
                || event.getTo() == null || !activePair(event.getFrom().getWorld(), event.getTo().getWorld())) {
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
                || crossing.teleport.isCancelled() || crossing.teleport.getTo() == null
                || !crossing.source.world().equals(event.getFrom().getUID())
                || !player.getWorld().equals(crossing.teleport.getTo().getWorld())
                || !activePair(event.getFrom(), player.getWorld())) return;
        // ChangedWorld is the success confirmation. PlayerPortalEvent#getTo is
        // only the pre-search position and is never used as an arrival portal.
        Portals.touching(player.getLocation()).ifPresent(destination -> {
            if (!Portals.usable(crossing.source, event.getFrom())
                    || !Portals.usable(destination, player.getWorld())) return;
            PortalLinked link = new PortalLinked(crossing.source, destination);
            player.getPersistentDataContainer().set(linkKey, PersistentDataType.BYTE_ARRAY, LinkCodec.encode(link));
            debug("Saved portal link for " + player.getName() + ".");
        });
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        contacts.remove(event.getPlayer().getUniqueId());
        pending.remove(event.getPlayer().getUniqueId());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("portallinked.admin")) {
            sender.sendMessage("You do not have permission to use PortalLinked admin commands.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(loadSettings() ? "PortalLinked configuration reloaded."
                    : "PortalLinked configuration could not be reloaded. Previous settings are unchanged; check the server log.");
        } else if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            Settings active = settings;
            sender.sendMessage("PortalLinked: enabled=" + active.enabled + ", excluded-worlds="
                    + active.excluded + ", debug=" + active.debug + ".");
        } else if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("forget")) {
            Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1])
                    : sender instanceof Player player ? player : null;
            if (target == null) {
                sender.sendMessage("Specify an online player: /portallinked forget <player>.");
            } else if (folia != null) folia.forget(sender, target);
            else { forget(target); sender.sendMessage("Forgot portal link for " + target.getName() + "."); }
        } else {
            sender.sendMessage("Usage: /portallinked <reload|status|forget> [online-player]");
        }
        return true;
    }

    private synchronized boolean loadSettings() {
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(new File(getDataFolder(), "config.yml"));
            Object enabledValue = candidate.get("enabled");
            Object worldsValue = candidate.get("excluded-worlds");
            Object debugValue = candidate.get("debug");
            if (!(enabledValue instanceof Boolean)) throw new IllegalArgumentException("enabled must be true or false");
            if (!(debugValue instanceof Boolean)) throw new IllegalArgumentException("debug must be true or false");
            if (!(worldsValue instanceof List<?> worlds)
                    || worlds.stream().anyMatch(value -> !(value instanceof String name) || name.isBlank()))
                throw new IllegalArgumentException("excluded-worlds must be a list of non-empty world names");
            Set<String> nextExcluded = Set.copyOf(worlds.stream().map(String.class::cast).toList());
            // Publish validated settings together. Failure must not change active
            // behavior or erase an in-flight crossing.
            settings = new Settings((Boolean) enabledValue, nextExcluded, (Boolean) debugValue, settings.generation + 1);
            contacts.clear();
            pending.clear();
            if (folia != null) folia.clear();
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException ex) {
            getLogger().warning("Cannot load config.yml (" + ex.getClass().getSimpleName()
                    + "). Check YAML syntax and the enabled/excluded-worlds/debug types; previous settings are unchanged.");
            return false;
        }
    }

    void debug(String message) {
        if (settings.debug) getLogger().info(message);
    }

    private Optional<PortalRef> source(Player player, Location from) {
        Contact contact = contacts.remove(player.getUniqueId());
        if (contact != null && player.getTicksLived() - contact.tick <= 2
                && contact.block.getWorld().equals(from.getWorld())
                && contact.block.distanceSquared(from) < 9) {
            Optional<PortalRef> portal = Portals.at(contact.block.getBlock());
            if (portal.isPresent()) return portal;
        }
        return Portals.touching(from);
    }

    Optional<PortalLinked> readLink(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(linkKey, PersistentDataType.BYTE_ARRAY);
        Optional<PortalLinked> link = LinkCodec.decode(bytes);
        if (bytes != null && link.isEmpty()) player.getPersistentDataContainer().remove(linkKey);
        return link;
    }

    boolean activePair(World from, World to) {
        Settings active = settings;
        if (!active.enabled || from == null || to == null
                || active.excluded.contains(from.getName()) || active.excluded.contains(to.getName())) return false;
        return (from.getEnvironment() == World.Environment.NORMAL && to.getEnvironment() == World.Environment.NETHER)
                || (from.getEnvironment() == World.Environment.NETHER && to.getEnvironment() == World.Environment.NORMAL);
    }

    long generation() { return settings.generation; }

    void saveLink(Player player, PortalRef source, PortalRef destination) {
        player.getPersistentDataContainer().set(linkKey, PersistentDataType.BYTE_ARRAY,
                LinkCodec.encode(new PortalLinked(source, destination)));
        debug("Saved portal link for " + player.getName() + ".");
    }

    void forget(Player player) {
        player.getPersistentDataContainer().remove(linkKey);
        contacts.remove(player.getUniqueId()); pending.remove(player.getUniqueId());
        if (folia != null) folia.forget(player.getUniqueId());
    }

    private record Settings(boolean enabled, Set<String> excluded, boolean debug, long generation) {}

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
