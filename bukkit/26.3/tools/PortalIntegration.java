// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Axis;
import org.bukkit.attribute.Attribute;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

public final class PortalIntegration extends JavaPlugin implements Listener {
    private final List<String> passed = new ArrayList<>();
    private final List<ServerPlayer> bots = new ArrayList<>();
    private final Map<UUID, List<String>> messages = new HashMap<>();
    private final NamespacedKey key = new NamespacedKey("portallinked", "last_crossing");
    private World overworld, nether;
    private MinecraftServer server;
    private Class<?> portals;
    private JavaPlugin subject;
    private boolean cancelPortal, cancelTeleport;
    private Location redirectTeleport;
    private PlayerPortalEvent lastPortal;
    private Location vanillaOrigin;
    private int vanillaRadius;
    private boolean vanillaCreate;
    private static final UUID PERSIST_ID = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789001");

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskLater(this, this::run, 5);
    }

    private void run() {
        Throwable failure = null;
        try {
            server = (MinecraftServer) Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            overworld = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElseThrow();
            nether = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NETHER).findFirst().orElseThrow();
            subject = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PortalLinked");
            check(subject != null && subject.isEnabled(), "plugin enabled on " + Bukkit.getVersion());
            check(subject.getDescription().getAuthors().equals(List.of("EyeCrasher")), "EyeCrasher author metadata");
            portals = Class.forName("dev.eyecrasher.portallinked.Portals", true, subject.getClass().getClassLoader());
            if (System.getProperty("portallinked.scenario", "").equals("blocked-return")) blockedReturns();
            else if (System.getProperty("portallinked.scenario", "").equals("swept-contact")) sweptContacts();
            else if (System.getProperty("portallinked.scenario", "").equals("departure-contact")) {
                departureContacts();
                adjacentArrival(Axis.X);
                adjacentArrival(Axis.Z);
            }
            else if (System.getProperty("portal.phase", "1").equals("2")) restartChecks();
            else {
                mechanics();
                ceilingChecks();
                adminChecks();
            }
        } catch (Throwable ex) {
            failure = ex;
            getLogger().log(Level.SEVERE, "INTEGRATION FAILURE", ex);
        } finally {
            try {
                String error = failure == null ? "null" : "\"" + failure.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                String cases = passed.stream().map(s -> "\"" + s.replace("\"", "\\\"") + "\"").collect(java.util.stream.Collectors.joining(","));
                Files.writeString(Path.of("result-" + System.getProperty("portal.phase", "1") + ".json"),
                        "{\"checks\":[" + cases + "],\"failure\":" + error + "}\n");
            } catch (Exception ex) { getLogger().log(Level.SEVERE, "Writing result", ex); }
            // Finish the bulk test tick before requesting shutdown. Paper's
            // shutdown watchdog uses a one-second threshold after running=false.
            getServer().getScheduler().runTaskLater(this, Bukkit::shutdown, 2);
        }
    }

    private void mechanics() throws Exception {
        sweptContacts();
        blockedReturns();
        departureContacts();
        adjacentArrival(Axis.X);
        adjacentArrival(Axis.Z);
        frame(overworld, 832, 64, 0, Axis.X, 4, 5);
        frame(overworld, 800, 64, 0, Axis.X, 2, 3);
        frame(nether, 100, 64, 0, Axis.Z, 5, 6);
        Object home = portal(new Location(overworld, 832.5, 64, 0.5));
        check(home != null, "valid framed portal detected");
        check(home.equals(portal(new Location(overworld, 835.5, 68, 0.5))), "all blocks of large portal have one identity");
        frame(overworld, -64, 64, -32, Axis.X, 21, 21);
        check(portal(new Location(overworld, -43.5, 84, -31.5)) != null, "maximum 21 by 21 portal and negative coordinates");
        frame(nether, -64, 64, -32, Axis.Z, 21, 21);
        check(portal(new Location(nether, -63.5, 84, -11.5)) != null, "maximum portal on Z axis");

        ServerPlayer vanilla = bot("VanillaProbe", UUID.randomUUID(), nether, 100.5, 64, 0.5);
        travel(vanilla);
        check(vanilla.level().getWorld().equals(overworld) && vanilla.getX() < 810, "vanilla shared portal chooses neighboring base without a saved journey");

        ServerPlayer one = bot("PortalOne", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        travel(one);
        check(one.level().getWorld().equals(nether) && Math.abs(one.getX() - 100.5) < 2, "first actual Paper portal transition reaches shared Nether portal");
        check(vanillaOrigin.equals(lastPortal.getTo()) && vanillaRadius == lastPortal.getSearchRadius()
                && vanillaCreate == lastPortal.getCanCreatePortal(), "first journey preserves vanilla search and creation settings");
        check(data(one) != null, "successful world change saves player-specific pair");
        ServerPlayer two = bot(".BedrockShape", UUID.randomUUID(), overworld, 800.5, 64, 0.5);
        travel(two);
        check(two.level().getWorld().equals(nether), "second player reaches the same Nether portal");
        travel(one);
        check(one.level().getWorld().equals(overworld) && one.getX() >= 832 && one.getX() < 837, "return fixes vanilla wrong-portal case");
        check(lastPortal.getSearchRadius() == 0 && !lastPortal.getCanCreatePortal(), "return narrows search to intact original portal");
        travel(two);
        check(two.getX() >= 800 && two.getX() < 803, "shared portal returns second UUID to its own base");
        one.snapTo(835.5, 67, 0.5);
        travel(one);
        check(one.level().getWorld().equals(nether), "return through a different block of the same large frame works");

        byte[] beforeCancel = data(one).clone();
        cancelPortal = true;
        TeleportTransition cancelled = destination(one);
        cancelPortal = false;
        check(cancelled == null && Arrays.equals(beforeCancel, data(one)), "cancelled PlayerPortalEvent preserves saved pair");
        cancelTeleport = true;
        TeleportTransition exit = destination(one);
        check(exit != null && one.teleport(exit) == null, "final teleport protection can cancel a corrected return");
        cancelTeleport = false;
        check(one.level().getWorld().equals(nether) && Arrays.equals(beforeCancel, data(one)), "cancelled final teleport changes neither world nor link");

        redirectTeleport = new Location(overworld, 3000.5, 70, 0.5);
        travel(one);
        redirectTeleport = null;
        check(one.level().getWorld().equals(overworld) && one.getX() == 3000.5, "another plugin can redirect final teleport");
        check(Arrays.equals(beforeCancel, data(one)), "arrival outside a portal does not invent a portal pair");
        relocate(one, nether, 100.5, 64, 0.5);

        clearPortal(overworld, 832, 64, 0, Axis.X, 4, 5);
        check(portal(new Location(overworld, 832.5, 64, 0.5)) == null, "destroyed portal rejected");
        travel(one);
        check(one.getX() >= 800 && one.getX() < 803 && lastPortal.getSearchRadius() > 0
                && lastPortal.getCanCreatePortal(), "destroyed return portal falls back to vanilla neighbor");
        frame(overworld, 832, 64, 0, Axis.X, 4, 5);

        ServerPlayer border = bot("BorderProbe", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        travel(border);
        overworld.getWorldBorder().setCenter(0, 0);
        overworld.getWorldBorder().setSize(1620);
        travel(border);
        check(border.getX() < 810 && lastPortal.getSearchRadius() > 0, "world border shrink prevents forced out-of-bounds return");
        overworld.getWorldBorder().setSize(59_999_968);

        ServerPlayer corrupt = bot("CorruptProbe", UUID.randomUUID(), nether, 100.5, 64, 0.5);
        corrupt.getBukkitEntity().getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY, new byte[]{127, 3});
        travel(corrupt);
        check(corrupt.getX() < 810 && data(corrupt).length == 63, "corrupt saved data recovers through a successful vanilla journey");

        frame(nether, 300, 64, 0, Axis.X, 2, 3);
        relocate(one, nether, 300.5, 64, 0.5);
        travel(one);
        check(one.level().getWorld().equals(overworld) && Math.abs(one.getX() - 2404) < 25,
                "new Nether portal creates Overworld exit at 1 to 8 distance");
        check(vanillaOrigin.equals(lastPortal.getTo()) && vanillaRadius == lastPortal.getSearchRadius()
                && lastPortal.getCanCreatePortal(), "different portal preserves vanilla search and creation");
        check(portal(one.getBukkitEntity().getLocation()) != null, "newly generated exit is an actual active portal");
        travel(one);
        check(one.level().getWorld().equals(nether) && Math.abs(one.getX() - 300.5) < 3,
                "newly created route also remembers its return");

        PlayerPortalEvent end = new PlayerPortalEvent(one.getBukkitEntity(), one.getBukkitEntity().getLocation(),
                new Location(overworld, 17, 70, 19), PlayerTeleportEvent.TeleportCause.END_PORTAL, 123, true, 9);
        Location endTarget = end.getTo().clone();
        Bukkit.getPluginManager().callEvent(end);
        check(end.getTo().equals(endTarget) && end.getSearchRadius() == 123 && end.getCanCreatePortal(), "End portal event remains unchanged");
        org.bukkit.entity.Entity pig = nether.spawnEntity(new Location(nether, 100.5, 64, 0.5), org.bukkit.entity.EntityType.PIG);
        EntityPortalEvent mob = new EntityPortalEvent(pig, pig.getLocation(), new Location(overworld, 800, 64, 0), 128, true, 16);
        Bukkit.getPluginManager().callEvent(mob);
        check(mob.getSearchRadius() == 128 && mob.getCanCreatePortal(), "non-player portal event remains unchanged");
        pig.remove();

        ServerPlayer persistence = bot("PersistProbe", PERSIST_ID, overworld, 832.5, 64, 0.5);
        travel(persistence);
        persistence.getBukkitEntity().saveData();
        Files.write(Path.of("expected-player-data.bin"), data(persistence));
        check(Files.exists(server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(PERSIST_ID + ".dat")), "pair saved in actual player data on disk");
        getLogger().info("MECHANICS PASS: " + passed.size());
    }

    private void restartChecks() throws Exception {
        ServerPlayer persistence = bot("PersistProbe", PERSIST_ID, nether, 100.5, 64, 0.5);
        persistence.getBukkitEntity().loadData();
        byte[] saved = Files.readAllBytes(Path.of("expected-player-data.bin"));
        check(Arrays.equals(saved, data(persistence)), "saved pair survives actual Paper shutdown and restart");
        Player operator = persistence.getBukkitEntity();
        operator.setOp(true);
        try {
            Bukkit.dispatchCommand(operator, "portallinked status");
            check(lastMessage(persistence).equals("PortalLinked: enabled=false, excluded-worlds=[], debug=false."),
                    "null startup setting leaves linking disabled");
            vanillaRoundTrip(persistence, "invalid startup configuration");
            check(Arrays.equals(saved, data(persistence)), "invalid startup retains persisted player links");
            writeSettings(true, List.of(), false);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            check(lastMessage(persistence).equals("PortalLinked configuration reloaded."),
                    "valid reload recovers from invalid startup configuration");
        } finally {
            operator.setOp(false);
        }
        travel(persistence);
        check(persistence.level().getWorld().equals(overworld) && persistence.getX() >= 832 && persistence.getX() < 837,
                "remembered return still selects original portal after restart");
        getLogger().info("RESTART PASS: " + passed.size());
    }

    private void ceilingChecks() throws Exception {
        java.lang.reflect.Method paperConfig;
        try { paperConfig = level(nether).getClass().getMethod("paperConfig"); }
        catch (NoSuchMethodException unsupported) { return; }
        Object config = paperConfig.invoke(level(nether));
        Object environment = config.getClass().getField("environment").get(config);
        var ceiling = environment.getClass().getField("netherCeilingVoidDamageHeight");
        Object original = ceiling.get(environment);
        Class<?> disabled = Class.forName("io.papermc.paper.configuration.type.number.IntOr$Disabled");
        var height = disabled.getConstructor(OptionalInt.class);
        try {
            int x = 500;
            for (Axis axis : List.of(Axis.X, Axis.Z)) {
                ceiling.set(environment, disabled.getField("DISABLED").get(null));
                frame(nether, x, 130, 0, axis, 2, 3);
                frame(nether, x + 4, 64, 0, axis, 2, 3);
                frame(overworld, x * 8, 64, 0, axis, 2, 3);
                Object roof = portal(new Location(nether, x + 0.5, 130, 0.5));
                check(usable(roof, nether), axis + " roof portal is eligible while ceiling policy is disabled");
                ServerPlayer player = bot("Roof" + axis, UUID.randomUUID(), nether, x + 0.5, 130, 0.5);
                travel(player);
                check(player.level().getWorld().equals(overworld) && data(player) != null,
                        axis + " departure above the Nether roof saves a return pair");
                ceiling.set(environment, height.newInstance(OptionalInt.of(130)));
                check(!usable(roof, nether), axis + " portal at the live ceiling threshold is excluded");
                ceiling.set(environment, height.newInstance(OptionalInt.of(131)));
                check(usable(roof, nether), axis + " portal below the live ceiling threshold remains eligible");
                ceiling.set(environment, height.newInstance(OptionalInt.of(128)));
                travel(player);
                check(player.level().getWorld().equals(nether) && player.getY() < 128
                        && Math.abs(player.getX() - (x + 4.5)) < 3 && vanillaLookup(),
                        axis + " newly excluded saved roof return falls back to vanilla lower portal");
                ServerPlayer departing = bot("RoofExit" + axis, UUID.randomUUID(), nether, x + 0.5, 130, 0.5);
                travel(departing);
                check(departing.level().getWorld().equals(overworld) && data(departing) == null,
                        axis + " crossing from an excluded roof portal is not recorded");
                ServerPlayer arriving = bot("RoofArrival" + axis, UUID.randomUUID(), overworld, x * 8 + 0.5, 64, 0.5);
                redirectTeleport = new Location(nether, x + 0.5, 130, 0.5);
                travel(arriving);
                redirectTeleport = null;
                check(arriving.level().getWorld().equals(nether) && arriving.getY() == 130 && data(arriving) == null,
                        axis + " crossing redirected to an excluded roof portal is not recorded");
                x += 100;
            }
        } finally {
            redirectTeleport = null;
            ceiling.set(environment, original);
        }
    }

    private void adminChecks() throws Exception {
        frame(overworld, 832, 64, 0, Axis.X, 4, 5);
        frame(overworld, 800, 64, 0, Axis.X, 2, 3);
        frame(nether, 100, 64, 0, Axis.Z, 5, 6);
        ServerPlayer user = bot("ConfigPlayer", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        ServerPlayer neighbor = bot("ConfigNeighbor", UUID.randomUUID(), overworld, 800.5, 64, 0.5);
        ServerPlayer admin = bot("AdminProbe", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        Player operator = admin.getBukkitEntity();
        List<String> logs = new ArrayList<>();
        Handler recorder = new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        subject.getLogger().addHandler(recorder);
        operator.setOp(true);
        try {
            check(subject.getConfig().getBoolean("enabled") && subject.getConfig().getStringList("excluded-worlds").isEmpty()
                    && !subject.getConfig().getBoolean("debug"), "bundled configuration enables ordinary play with debug off");
            check(Bukkit.getPluginManager().getPermission("portallinked.admin").getDefault() == PermissionDefault.OP
                    && operator.hasPermission("portallinked.admin") && !user.getBukkitEntity().hasPermission("portallinked.admin"),
                    "admin permission defaults to operators and denies ordinary players");
            travel(user);
            travel(neighbor);
            byte[] neighborLink = data(neighbor).clone();
            byte[] userLink = data(user).clone();
            for (String action : List.of("status", "forget ConfigNeighbor")) {
                boolean dispatched = Bukkit.dispatchCommand(user.getBukkitEntity(), "portallinked " + action);
                check((!dispatched || lastMessage(user).equals("You do not have permission to use PortalLinked admin commands."))
                        && Arrays.equals(userLink, data(user)) && Arrays.equals(neighborLink, data(neighbor)),
                        "ordinary player denied admin command " + action);
            }
            writeSettings(false, List.of(), false);
            boolean reloadDispatched = Bukkit.dispatchCommand(user.getBukkitEntity(), "portallinked reload");
            check(!reloadDispatched || lastMessage(user).equals("You do not have permission to use PortalLinked admin commands."),
                    "ordinary player denied configuration reload");
            travel(user);
            check(user.getX() >= 832 && user.getX() < 837 && lastPortal.getSearchRadius() == 0,
                    "disk changes and denied reload leave active configuration unchanged");
            travel(user);
            check(logs.isEmpty(), "ordinary travel produces no debug logs by default");
            Bukkit.dispatchCommand(operator, "portallinked status");
            check(lastMessage(admin).equals("PortalLinked: enabled=true, excluded-worlds=[], debug=false."),
                    "admin status reports active configuration");
            Bukkit.dispatchCommand(operator, "portallinked reload");
            check(lastMessage(admin).equals("PortalLinked configuration reloaded."), "operator can reload configuration");
            Bukkit.dispatchCommand(operator, "portallinked status");
            check(lastMessage(admin).equals("PortalLinked: enabled=false, excluded-worlds=[], debug=false."),
                    "reload applies configuration from disk");
            vanillaRoundTrip(user, "disabled mode");
            invalidSettingsChecks(user, admin, "disabled mode", false);
            for (World excluded : List.of(overworld, nether)) {
                writeSettings(true, List.of(excluded.getName()), true);
                Bukkit.dispatchCommand(operator, "portallinked reload");
                vanillaRoundTrip(user, "excluded world " + excluded.getName());
                invalidSettingsChecks(user, admin, "excluded world " + excluded.getName(), false);
            }
            writeSettings(true, List.of("unused-world"), true);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            invalidSettingsChecks(user, admin, "enabled mode", true);
            logs.clear();
            writeSettings(true, List.of(overworld.getName().toUpperCase(Locale.ROOT)), true);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            travel(user);
            check(user.getX() >= 832 && user.getX() < 837 && lastPortal.getSearchRadius() == 0,
                    "world exclusions use exact case-sensitive names");
            check(logs.stream().anyMatch(line -> line.endsWith("Returning ConfigPlayer to their saved portal."))
                    && logs.stream().anyMatch(line -> line.endsWith("Saved portal link for ConfigPlayer.")), "debug reload enables concise routing and recording logs");
            writeSettings(true, List.of(), false);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            logs.clear();
            travel(user);
            check(logs.isEmpty(), "reload disables debug logs again");
            Bukkit.dispatchCommand(operator, "portallinked forget ConfigNeigh");
            check(lastMessage(admin).equals("Specify an online player: /portallinked forget <player>.")
                    && Arrays.equals(neighborLink, data(neighbor)), "forget requires an exact online player name");
            Bukkit.dispatchCommand(operator, "portallinked forget ConfigPlayer");
            check(data(user) == null && Arrays.equals(neighborLink, data(neighbor))
                    && lastMessage(admin).equals("Forgot portal link for ConfigPlayer."),
                    "admin forget removes only the selected player's saved pair");
            travel(user);
            check(user.getX() >= 800 && user.getX() < 803 && vanillaLookup(), "forgotten return uses vanilla lookup and records a new journey");
            travel(admin);
            check(data(admin) != null, "operator also has an ordinary player-specific journey");
            Bukkit.dispatchCommand(operator, "portallinked forget");
            check(data(admin) == null && Arrays.equals(neighborLink, data(neighbor)), "forget without a player resets only the sender");
            byte[] retained = data(user).clone();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked forget ConfigNeighbor");
            check(data(neighbor) == null && Arrays.equals(retained, data(user)), "console can forget one named online player");
            relocate(user, overworld, 832.5, 64, 0.5);
            travel(user);
            TeleportTransition inFlight = destination(user);
            Bukkit.dispatchCommand(operator, "portallinked forget ConfigPlayer");
            check(inFlight != null && user.teleport(inFlight) != null && data(user) == null,
                    "forget clears an in-flight crossing so arrival cannot recreate the forgotten pair");
            relocate(user, nether, 100.5, 64, 0.5);
            inFlight = destination(user);
            writeSettings(false, List.of(), false);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            check(inFlight != null && user.teleport(inFlight) != null && data(user) == null,
                    "reload clears an in-flight crossing before disabled arrival recording");
        } finally {
            subject.getLogger().removeHandler(recorder);
            operator.setOp(false);
            writeSettings(true, List.of(), false);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked reload");
        }
    }

    private void invalidSettingsChecks(ServerPlayer player, ServerPlayer admin, String mode, boolean active) throws Exception {
        Path file = subject.getDataFolder().toPath().resolve("config.yml");
        String valid = Files.readString(file);
        Player operator = admin.getBukkitEntity();
        Bukkit.dispatchCommand(operator, "portallinked status");
        String status = lastMessage(admin);
        for (var invalid : invalidSettings().entrySet()) {
            String description = mode + ": " + invalid.getKey();
            byte[] saved = data(player).clone();
            TeleportTransition inFlight = active ? destination(player) : null;
            Files.writeString(file, invalid.getValue());
            Bukkit.dispatchCommand(operator, "portallinked reload");
            check(lastMessage(admin).startsWith("PortalLinked configuration could not be reloaded."),
                    description + " reports reload failure");
            Bukkit.dispatchCommand(operator, "portallinked status");
            check(lastMessage(admin).equals(status) && Arrays.equals(saved, data(player)),
                    description + " preserves all active settings and saved links");
            if (active) {
                check(inFlight != null && player.teleport(inFlight) != null && !Arrays.equals(saved, data(player)),
                        description + " retains in-flight crossing for successful arrival recording");
                travel(player);
                check(Arrays.equals(saved, data(player)) && lastPortal.getSearchRadius() == 0
                        && !lastPortal.getCanCreatePortal(), description + " retains ordinary remembered return");
            } else {
                vanillaRoundTrip(player, description);
            }
        }
        Files.writeString(file, valid);
        Bukkit.dispatchCommand(operator, "portallinked reload");
        check(lastMessage(admin).equals("PortalLinked configuration reloaded."), mode + " recovers with a valid reload");
        Bukkit.dispatchCommand(operator, "portallinked status");
        check(lastMessage(admin).equals(status), mode + " recovery applies all valid settings");
    }

    private Map<String, String> invalidSettings() {
        String valid = "enabled: true\nexcluded-worlds: []\ndebug: false\n";
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("malformed YAML", "enabled: false\nexcluded-worlds: [world_nether\ndebug: false\n");
        invalid.put("string enabled", valid.replace("enabled: true", "enabled: 'true'"));
        invalid.put("string debug", valid.replace("debug: false", "debug: 'false'"));
        for (String value : List.of("world_nether", "[42]", "['']"))
            invalid.put("invalid excluded-worlds " + value, valid.replace("excluded-worlds: []", "excluded-worlds: " + value));
        for (String line : List.of("enabled: true\n", "excluded-worlds: []\n", "debug: false\n")) {
            String name = line.substring(0, line.indexOf(':'));
            for (String value : List.of("null", "", "''"))
                invalid.put(name + " value '" + value + "'", valid.replace(line, name + ": " + value + "\n"));
            invalid.put("missing " + name, valid.replace(line, ""));
        }
        invalid.put("empty configuration", "");
        return invalid;
    }

    private void vanillaRoundTrip(ServerPlayer player, String mode) {
        byte[] saved = data(player).clone();
        travel(player);
        check(player.level().getWorld().equals(overworld) && player.getX() >= 800 && player.getX() < 803
                && vanillaLookup() && Arrays.equals(saved, data(player)), mode + " skips return routing and recording toward Overworld");
        travel(player);
        check(player.level().getWorld().equals(nether) && vanillaLookup() && Arrays.equals(saved, data(player)),
                mode + " skips return routing and recording toward Nether");
    }

    private void writeSettings(boolean enabled, List<String> excludedWorlds, boolean debug) throws Exception {
        var file = subject.getDataFolder().toPath().resolve("config.yml").toFile();
        var config = new YamlConfiguration();
        config.set("enabled", enabled);
        config.set("excluded-worlds", excludedWorlds);
        config.set("debug", debug);
        config.save(file);
    }

    private String lastMessage(ServerPlayer player) {
        return org.bukkit.ChatColor.stripColor(messages.getOrDefault(player.getUUID(), List.of()).getLast());
    }

    private boolean vanillaLookup() {
        return vanillaOrigin.equals(lastPortal.getTo()) && vanillaRadius == lastPortal.getSearchRadius()
                && vanillaCreate == lastPortal.getCanCreatePortal();
    }

    private ServerPlayer bot(String name, UUID id, World world, double x, double y, double z) {
        GameProfile profile = new GameProfile(id, name);
        ServerLevel level = level(world);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        nativeField(Connection.class, connection, "channel", new EmbeddedChannel());
        nativeField(Connection.class, connection, "address", new InetSocketAddress("127.0.0.1", 54321));
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof ClientboundSystemChatPacket chat)
                    messages.computeIfAbsent(id, ignored -> new ArrayList<>()).add(chat.content().getString());
            }
            public void send(Packet<?> packet, ChannelFutureListener listener) { send(packet); }
        };
        player.snapTo(x, y, z);
        player.getBukkitEntity().setInvulnerable(true);
        server.getPlayerList().getPlayers().add(player);
        try {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, ServerPlayer> byId = (Map<UUID, ServerPlayer>) field.get(server.getPlayerList());
            byId.put(id, player);
        } catch (ReflectiveOperationException ex) { throw new AssertionError("Cannot register fixture UUID", ex); }
        try {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByName");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ServerPlayer> byName = (Map<String, ServerPlayer>) field.get(server.getPlayerList());
            byName.put(name.toLowerCase(Locale.ROOT), player);
        } catch (NoSuchFieldException noNameIndex) {
            // Spigot resolves names from its registered player list.
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot register native fixture player", ex);
        }
        level.addNewPlayer(player);
        bots.add(player);
        return player;
    }

    private TeleportTransition destination(ServerPlayer player) {
        lastPortal = null;
        player.getBukkitEntity().setPortalCooldown(0);
        PortalProcessor process = new PortalProcessor((NetherPortalBlock) Blocks.NETHER_PORTAL, player.blockPosition());
        process.setAsInsidePortalThisTick(false);
        nativeField(Entity.class, player, "portalProcess", process);
        inside(player, player.blockPosition());
        return ((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalDestination(player.level(), player, player.blockPosition());
    }

    private void departureContacts() throws Exception {
        for (Axis axis : new Axis[] {Axis.X, Axis.Z}) for (boolean homeNether : new boolean[] {false, true}) {
            boolean x = axis == Axis.X;
            World home = homeNether ? nether : overworld, shared = homeNether ? overworld : nether;
            int base = homeNether ? 24004 : 168032, target = homeNether ? base * 8 : base / 8;
            String label = "axis=" + axis + " homeNether=" + homeNether;
            for (World world : new World[] {home, shared}) {
                int start = world == home ? base : target;
                for (int along = start - 3; along <= start + 7; along++) for (int across = -3; across <= 4; across++)
                    for (int y = 64; y <= 74; y++) world.getBlockAt(x ? along : across, y, x ? across : along).setType(Material.AIR, false);
            }
            frame(home, x ? base : 0, 64, x ? 0 : base, axis, 4, 8);
            frame(home, x ? base : 1, 64, x ? 1 : base, axis, 4, 8, false);
            frame(shared, x ? target : 0, 64, x ? 0 : target, axis, 4, 8);
            ServerPlayer probe = bot("Departure" + axis + homeNether, UUID.randomUUID(), home,
                    x ? base + 2 : .9, 64, x ? .9 : base + 2);
            probe.getBukkitEntity().getAttribute(Attribute.SCALE).setBaseValue(2);
            probe.setPose(Pose.CROUCHING); probe.refreshDimensions();
            var bounds = probe.getBoundingBox();
            check(bounds.getXsize() > 1.1 && bounds.getYsize() >= 2.9 && bounds.getYsize() < 3.1
                    && (x ? bounds.minZ < 1 && bounds.maxZ > 1 : bounds.minX < 1 && bounds.maxX > 1),
                    "crouching scaled body overlaps both departure planes: " + label);
            check(home.getBlockAt(x ? base : 0, 64, x ? 0 : base).getType() == Material.NETHER_PORTAL
                    && home.getBlockAt(x ? base : 1, 64, x ? 1 : base).getType() == Material.NETHER_PORTAL,
                    "both departure frames coexist: " + label);
            BlockPos first = new BlockPos(x ? base + 2 : 0, 64, x ? 0 : base + 2);
            BlockPos second = new BlockPos(x ? base + 2 : 1, 64, x ? 1 : base + 2);
            PortalProcessor process = new PortalProcessor((NetherPortalBlock) Blocks.NETHER_PORTAL, first);
            process.setAsInsidePortalThisTick(false);
            nativeField(PortalProcessor.class, process, "portalTime",
                    ((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalTransitionTime(probe.level(), probe));
            nativeField(Entity.class, probe, "portalProcess", process);
            probe.getBukkitEntity().setPortalCooldown(0);
            inside(probe, first); inside(probe, second);
            check(process.getEntryPosition().equals(first), "native processor retains first accepted departure: " + label);
            var handle = Entity.class.getDeclaredMethod("handlePortal"); handle.setAccessible(true); handle.invoke(probe);
            check(probe.level().getWorld().equals(shared) && data(probe) != null,
                    "native overlapping departure completes and saves: " + label);
            check(java.nio.ByteBuffer.wrap(data(probe)).getInt(x ? 25 : 17) == 0,
                    "saved departure matches native first contact: " + label);
            travel(probe);
            check(probe.level().getWorld().equals(home) && Math.abs((x ? probe.getZ() : probe.getX()) - .5) < .1,
                    "remembered round trip reaches native departure plane: " + label);
        }
    }

    private void sweptContacts() {
        for (boolean saved : new boolean[] {true, false}) for (Axis axis : new Axis[] {Axis.X, Axis.Z})
            for (boolean homeNether : new boolean[] {false, true}) {
                boolean x = axis == Axis.X;
                World home = homeNether ? nether : overworld, shared = homeNether ? overworld : nether;
                int base = (homeNether ? 40004 : 224032) + (saved ? 0 : homeNether ? 4000 : 16000);
                int fallback = base - (homeNether ? 8 : 32), other = homeNether ? fallback * 8 : fallback / 8;
                String label = "axis=" + axis + " homeNether=" + homeNether + " saved=" + saved;
                frame(home, x ? base : 0, 64, x ? 0 : base, axis, 2, 3);
                frame(home, x ? fallback : 0, 64, x ? 0 : fallback, axis, 2, 3);
                frame(shared, x ? other : 0, 64, x ? 0 : other, axis, 2, 3);
                ServerPlayer probe = bot("Sweep" + axis + homeNether + saved, UUID.randomUUID(), home,
                        x ? base + .5 : .5, 64, x ? .5 : base + .5);
                if (saved) {
                    travel(probe);
                    check(probe.level().getWorld().equals(shared) && Math.abs((x ? probe.getX() : probe.getZ()) - other) < 2,
                            "swept return probe starts at its actual shared arrival: " + label);
                }
                probe.getBukkitEntity().setGameMode(org.bukkit.GameMode.CREATIVE);
                World target = saved ? home : shared;
                int along = saved ? other : base;
                Vec3 from = new Vec3(x ? along + .5 : -.5, 64, x ? -.5 : along + .5);
                Vec3 to = new Vec3(x ? along + .5 : 1.5, 64, x ? 1.5 : along + .5);
                probe.snapTo(to.x, to.y, to.z);
                probe.getBukkitEntity().setPortalCooldown(0);
                nativeField(Entity.class, probe, "portalProcess", null);
                check((x ? probe.getBoundingBox().minZ : probe.getBoundingBox().minX) > 1,
                        "swept player ends outside the contacted frame: " + label);
                probe.applyEffectsFromBlocks(from, to);
                try {
                    var field = Entity.class.getDeclaredField("portalProcess"); field.setAccessible(true);
                    PortalProcessor process = (PortalProcessor) field.get(probe);
                    check(process != null && process.isInsidePortalThisTick(), "native swept movement accepts the portal: " + label);
                    var handle = Entity.class.getDeclaredMethod("handlePortal"); handle.setAccessible(true); handle.invoke(probe);
                } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
                check(probe.level().getWorld().equals(target) && data(probe) != null,
                        "swept crossing completes and records its pair: " + label);
                if (saved) {
                    check(Math.abs((x ? probe.getX() : probe.getZ()) - base) < 2,
                            "swept saved return chooses original frame instead of native neighbor: " + label);
                } else {
                    check(java.nio.ByteBuffer.wrap(data(probe)).getInt(x ? 17 : 25) == base,
                            "swept first trip records actual departure: " + label);
                    travel(probe);
                    check(probe.level().getWorld().equals(home) && Math.abs((x ? probe.getX() : probe.getZ()) - base) < 2,
                            "recorded swept departure supports a personal return: " + label);
                }
            }
    }

    private void inside(ServerPlayer player, BlockPos contact) {
        try {
            var inside = NetherPortalBlock.class.getDeclaredMethod("entityInside", BlockState.class, net.minecraft.world.level.Level.class,
                    BlockPos.class, Entity.class, InsideBlockEffectApplier.class, boolean.class);
            inside.setAccessible(true);
            inside.invoke(Blocks.NETHER_PORTAL, player.level().getBlockState(contact), player.level(), contact,
                    player, InsideBlockEffectApplier.NOOP, true);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private void adjacentArrival(Axis axis) {
        boolean x = axis == Axis.X;
        int base = x ? 120032 : 128032, target = base / 8 - 4;
        frame(overworld, x ? base : 8, 64, x ? 8 : base, axis, 2, 3);
        frame(nether, x ? target : 0, 64, x ? 0 : target, axis, 2, 3);
        frame(nether, x ? target : 1, 64, x ? 1 : target, axis, 2, 3, false);
        check(nether.getBlockAt(x ? target : 0, 64, x ? 0 : target).getType() == Material.NETHER_PORTAL
                && nether.getBlockAt(x ? target : 1, 64, x ? 1 : target).getType() == Material.NETHER_PORTAL,
                "both adjacent portal planes coexist before native arrival on axis " + axis);
        ServerPlayer probe = bot("Arrival" + axis.name(), UUID.randomUUID(), overworld,
                x ? base + .5 : 8.1, 64, x ? 8.1 : base + .5);
        travel(probe);
        check((x ? probe.getZ() : probe.getX()) >= 1 && (x ? probe.getZ() : probe.getX()) < 2,
                "native arrival chooses adjacent plane 1 on axis " + axis);
        check(java.nio.ByteBuffer.wrap(data(probe)).getInt(x ? 56 : 48) == 1,
                "saved arrival identity excludes overlapping neighboring plane on axis " + axis);
        relocate(probe, nether, x ? target + .5 : 1.5, 64, x ? 1.5 : target + .5);
        travel(probe);
        check(Math.abs((x ? probe.getX() : probe.getZ()) - base) < 3,
                "centered adjacent portal returns to original base on axis " + axis);
    }

    private void blockedReturns() {
        for (Axis axis : new Axis[] {Axis.X, Axis.Z}) for (boolean homeNether : new boolean[] {false, true}) {
            boolean x = axis == Axis.X;
            World home = homeNether ? nether : overworld, shared = homeNether ? overworld : nether;
            int base = homeNether ? 20004 : 144032;
            int fallback = base - (homeNether ? 8 : 32), other = homeNether ? fallback * 8 : fallback / 8;
            for (World world : new World[] {home, shared}) {
                int start = world == home ? fallback - 3 : other - 3, end = world == home ? base + 5 : other + 5;
                for (int along = start; along <= end; along++) for (int across = -3; across <= 3; across++)
                    for (int y = 64; y <= 74; y++) world.getBlockAt(x ? along : across, y, x ? across : along).setType(Material.AIR, false);
            }
            frame(home, x ? base : 0, 64, x ? 0 : base, axis, 2, 3);
            frame(home, x ? fallback : 0, 64, x ? 0 : fallback, axis, 4, 8);
            frame(shared, x ? other : 0, 64, x ? 0 : other, axis, 4, 8);
            ServerPlayer probe = bot("Blocked" + axis + homeNether, UUID.randomUUID(), home,
                    x ? base + 1 : .5, 64, x ? .5 : base + 1);
            travel(probe); travel(probe);
            check(probe.level().getWorld().equals(home) && Math.abs((x ? probe.getX() : probe.getZ()) - base) < 3,
                    "small unobstructed saved return succeeds on " + axis + " homeNether=" + homeNether);
            travel(probe);
            probe.getBukkitEntity().getAttribute(Attribute.SCALE).setBaseValue(4); probe.refreshDimensions();
            probe.snapTo(x ? other + 2 : .5, 64, x ? .5 : other + 2);
            check(probe.getBoundingBox().getXsize() > 2 && probe.getBoundingBox().getYsize() > 7,
                    "blocked-return probe has actual enlarged bounds on " + axis + " homeNether=" + homeNether);
            travel(probe);
            check(vanillaOrigin.equals(lastPortal.getTo()) && vanillaRadius == lastPortal.getSearchRadius()
                    && vanillaCreate == lastPortal.getCanCreatePortal(),
                    "blocked remembered frame preserves native lookup settings on " + axis + " homeNether=" + homeNether);
            check(probe.level().getWorld().equals(home) && Math.abs((x ? probe.getX() : probe.getZ()) - fallback) < 4
                    && probe.level().noBlockCollision(probe, probe.getBoundingBox()),
                    "enlarged return falls back to unobstructed native frame on " + axis + " homeNether=" + homeNether);
        }
    }

    private void travel(ServerPlayer player) {
        TeleportTransition transition = destination(player);
        if (transition == null) throw new AssertionError("No portal transition from " + player.getBukkitEntity().getLocation());
        if (player.teleport(transition) == null) throw new AssertionError("Teleport failed");
    }

    private void relocate(ServerPlayer player, World world, double x, double y, double z) {
        if (!player.getBukkitEntity().teleport(new Location(world, x, y, z), PlayerTeleportEvent.TeleportCause.PLUGIN))
            throw new AssertionError("Relocation failed");
    }

    private ServerLevel level(World world) {
        try { return (ServerLevel) world.getClass().getMethod("getHandle").invoke(world); }
        catch (ReflectiveOperationException ex) { throw new AssertionError("Cannot access test world", ex); }
    }

    private void nativeField(Class<?> type, Object target, String name, Object value) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) { throw new AssertionError("Cannot initialize test connection", ex); }
    }

    private byte[] data(ServerPlayer player) {
        return player.getBukkitEntity().getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
    }

    private Object portal(Location location) throws Exception {
        var method = portals.getDeclaredMethod("touching", Location.class);
        method.setAccessible(true);
        return ((Optional<?>) method.invoke(null, location)).orElse(null);
    }

    private boolean usable(Object portal, World world) throws Exception {
        var method = portals.getDeclaredMethod("usable", portal.getClass(), World.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, portal, world);
    }

    private void frame(World world, int x, int y, int z, Axis axis, int width, int height) {
        frame(world, x, y, z, axis, width, height, true);
    }

    private void frame(World world, int x, int y, int z, Axis axis, int width, int height, boolean clear) {
        int dx = axis == Axis.X ? 1 : 0, dz = axis == Axis.Z ? 1 : 0;
        for (int a = -1; a <= width; a++) for (int b = -1; b <= height; b++) {
            for (int side = -1; clear && side <= 1; side++) {
                int bx = x + dx * a + dz * side, bz = z + dz * a + dx * side;
                world.getBlockAt(bx, y + b, bz).setType(Material.AIR, false);
            }
            boolean edge = a == -1 || a == width || b == -1 || b == height;
            if (edge) world.getBlockAt(x + dx * a, y + b, z + dz * a).setType(Material.OBSIDIAN, false);
        }
        Orientable state = (Orientable) Material.NETHER_PORTAL.createBlockData();
        state.setAxis(axis);
        for (int a = 0; a < width; a++) for (int b = 0; b < height; b++)
            world.getBlockAt(x + dx * a, y + b, z + dz * a).setBlockData(state, false);
    }

    private void clearPortal(World world, int x, int y, int z, Axis axis, int width, int height) {
        for (int a = 0; a < width; a++) for (int b = 0; b < height; b++)
            world.getBlockAt(x + (axis == Axis.X ? a : 0), y + b, z + (axis == Axis.Z ? a : 0)).setType(Material.AIR, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void initial(PlayerPortalEvent event) {
        vanillaOrigin = event.getTo().clone();
        vanillaRadius = event.getSearchRadius();
        vanillaCreate = event.getCanCreatePortal();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void protectionPortal(PlayerPortalEvent event) {
        lastPortal = event;
        if (cancelPortal) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void protectionTeleport(PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent || event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        if (cancelTeleport) event.setCancelled(true);
        if (redirectTeleport != null) event.setTo(redirectTeleport.clone());
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed.add(description);
        getLogger().info("PASS " + description);
    }
}
