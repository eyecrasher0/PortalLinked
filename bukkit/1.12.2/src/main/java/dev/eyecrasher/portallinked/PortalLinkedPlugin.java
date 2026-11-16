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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PortalLinkedPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Contact> contacts = new ConcurrentHashMap<>();
    private final Map<UUID, Crossing> pending = new ConcurrentHashMap<>();
    private volatile Settings settings = new Settings(false, java.util.Collections.emptySet(), false, 0);

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!loadSettings()) getLogger().warning("Portal linking remains disabled until the configuration is fixed and reloaded.");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PortalLinked enabled.");
    }

    @Override public synchronized void onDisable() {
        settings = new Settings(false, settings.excluded, settings.debug, settings.generation + 1);
        contacts.clear();
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void enter(EntityPortalEnterEvent event) {
        if (settings.enabled && !settings.excluded.contains(event.getLocation().getWorld().getName())
                && event.getLocation().getBlock().getType() == Material.PORTAL
                && event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            contacts.remove(player.getUniqueId());
            Portals.departure(player, event.getLocation()).ifPresent(block -> contacts.put(player.getUniqueId(),
                    new Contact(block, player.getTicksLived(), event)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void route(PlayerPortalEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL || event.getTo() == null
                || !activePair(event.getFrom().getWorld(), event.getTo().getWorld())) return;
        PortalRef source = source(event.getPlayer()).orElse(null);
        if (source == null) return;
        Crossing crossing = new Crossing(source, event);
        pending.put(id, crossing);
        // A failed or cancelled portal search must never become a later crossing.
        getServer().getScheduler().runTask(this, () -> pending.remove(id, crossing));

        World target = event.getTo().getWorld();
        Optional<PortalRef> saved = readLink(event.getPlayer())
                .flatMap(link -> link.returnPortal(source, target.getUID()));
        if (!saved.isPresent()) return;
        PortalRef destination = saved.get();
        if (!Portals.usable(destination, target) || !Portals.safeReturn(event.getPlayer(), source, destination, target)) {
            debug("Saved return unavailable for " + event.getPlayer().getName() + "; using vanilla lookup.");
            return;
        }

        // The server still handles orientation, collisions and protection events.
        // A remembered return must not create a replacement portal.
        event.setTo(destination.searchOrigin(target));
        event.useTravelAgent(false);
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
        Portals.touching(player).ifPresent(destination -> {
            if (!Portals.usable(crossing.source, event.getFrom())
                    || !Portals.usable(destination, player.getWorld())) return;
            PortalLinked link = new PortalLinked(crossing.source, destination);
            if (LegacyPlayerData.write(player, LinkCodec.encode(link)))
                debug("Saved portal link for " + player.getName() + ".");
            else debug("Native player tag storage is full for " + player.getName() + "; keeping their previous link.");
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
                    : sender instanceof Player ? (Player) sender : null;
            if (target == null) {
                sender.sendMessage("Specify an online player: /portallinked forget <player>.");
            } else { forget(target); sender.sendMessage("Forgot portal link for " + target.getName() + "."); }
        } else {
            sender.sendMessage("Usage: /portallinked <reload|status|forget> [online-player]");
        }
        return true;
    }

    private boolean loadSettings() {
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(new File(getDataFolder(), "config.yml"));
            Object enabledValue = candidate.get("enabled");
            Object worldsValue = candidate.get("excluded-worlds");
            Object debugValue = candidate.get("debug");
            if (!(enabledValue instanceof Boolean)) throw new IllegalArgumentException("enabled must be true or false");
            if (!(debugValue instanceof Boolean)) throw new IllegalArgumentException("debug must be true or false");
            if (!(worldsValue instanceof List<?>)
                    || ((List<?>) worldsValue).stream().anyMatch(value -> !(value instanceof String) || ((String) value).codePoints().allMatch(Character::isWhitespace)))
                throw new IllegalArgumentException("excluded-worlds must be a list of non-empty world names");
            Set<String> nextExcluded = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(((List<?>) worldsValue).stream().map(String.class::cast).collect(java.util.stream.Collectors.toList())));
            // Publish validated settings together. Failure must not change active
            // behavior or erase an in-flight crossing.
            synchronized (this) {
                settings = new Settings((Boolean) enabledValue, nextExcluded, (Boolean) debugValue, settings.generation + 1);
                contacts.clear();
                pending.clear();
            }
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

    private Optional<PortalRef> source(Player player) {
        Contact contact = contacts.remove(player.getUniqueId());
        if (contact == null || (contact.event instanceof Cancellable && ((Cancellable) contact.event).isCancelled())
                || player.getTicksLived() - contact.tick > 2
                || !contact.block.getWorld().equals(player.getWorld())) return Optional.empty();
        // Native movement accepts swept contacts even after the body leaves the frame.
        return Portals.at(contact.block.getBlock());
    }

    Optional<PortalLinked> readLink(Player player) {
        byte[] bytes = LegacyPlayerData.read(player);
        return LinkCodec.decode(bytes);
    }

    boolean activePair(World from, World to) {
        return activePair(settings, from, to);
    }

    boolean activePair(Settings active, World from, World to) {
        if (!active.enabled || from == null || to == null
                || active.excluded.contains(from.getName()) || active.excluded.contains(to.getName())) return false;
        return (from.getEnvironment() == World.Environment.NORMAL && to.getEnvironment() == World.Environment.NETHER)
                || (from.getEnvironment() == World.Environment.NETHER && to.getEnvironment() == World.Environment.NORMAL);
    }

    Settings settingsSnapshot() { return settings; }

    /** Guard trip claims and data writes against reload; no waits or foreign callbacks. */
    synchronized boolean withSettings(Settings expected, Runnable action) {
        if (!isEnabled() || settings != expected || !expected.enabled) return false;
        action.run();
        return true;
    }

    void saveLink(Player player, PortalRef source, PortalRef destination) {
        if (LegacyPlayerData.write(player, LinkCodec.encode(new PortalLinked(source, destination))))
            debug("Saved portal link for " + player.getName() + ".");
        else debug("Native player tag storage is full for " + player.getName() + "; keeping their previous link.");
    }

    void forget(Player player) {
        LegacyPlayerData.clear(player);
        contacts.remove(player.getUniqueId()); pending.remove(player.getUniqueId());
    }

    final class Settings {
        private final boolean enabled;
        private final Set<String> excluded;
        private final boolean debug;
        private final long generation;

        public Settings(boolean enabled, Set<String> excluded, boolean debug, long generation) {
            this.enabled = enabled;
            this.excluded = excluded;
            this.debug = debug;
            this.generation = generation;
        }

        public boolean enabled() { return enabled; }
        public Set<String> excluded() { return excluded; }
        public boolean debug() { return debug; }
        public long generation() { return generation; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Settings)) return false;
            Settings value = (Settings) other;
            return java.util.Objects.equals(enabled, value.enabled) && java.util.Objects.equals(excluded, value.excluded) && java.util.Objects.equals(debug, value.debug) && java.util.Objects.equals(generation, value.generation);
        }
        @Override public int hashCode() { return java.util.Objects.hash(enabled, excluded, debug, generation); }
        @Override public String toString() { return "Settings[" + "enabled=" + enabled + ", " + "excluded=" + excluded + ", " + "debug=" + debug + ", " + "generation=" + generation + "]"; }
}

    private static final class Contact {
        private final Location block;
        private final int tick;
        private final Event event;

        public Contact(Location block, int tick, Event event) {
            this.block = block;
            this.tick = tick;
            this.event = event;
        }

        public Location block() { return block; }
        public int tick() { return tick; }
        public Event event() { return event; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Contact)) return false;
            Contact value = (Contact) other;
            return java.util.Objects.equals(block, value.block) && java.util.Objects.equals(tick, value.tick) && java.util.Objects.equals(event, value.event);
        }
        @Override public int hashCode() { return java.util.Objects.hash(block, tick, event); }
        @Override public String toString() { return "Contact[" + "block=" + block + ", " + "tick=" + tick + ", " + "event=" + event + "]"; }
}

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
