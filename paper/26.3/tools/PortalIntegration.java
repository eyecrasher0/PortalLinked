// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.configuration.type.number.IntOr;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
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
            server = ((CraftServer) Bukkit.getServer()).getServer();
            overworld = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElseThrow();
            nether = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NETHER).findFirst().orElseThrow();
            subject = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PortalLinked");
            check(subject != null && subject.isEnabled(), "plugin enabled on Paper " + Bukkit.getMinecraftVersion());
            check(subject.getDescription().getAuthors().equals(List.of("EyeCrasher")), "EyeCrasher author metadata");
            portals = Class.forName("dev.eyecrasher.portallinked.Portals", true, subject.getClass().getClassLoader());
            if (System.getProperty("portal.phase", "1").equals("2")) restartChecks();
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
        check(Arrays.equals(Files.readAllBytes(Path.of("expected-player-data.bin")), data(persistence)), "saved pair survives actual Paper shutdown and restart");
        travel(persistence);
        check(persistence.level().getWorld().equals(overworld) && persistence.getX() >= 832 && persistence.getX() < 837,
                "remembered return still selects original portal after restart");
        getLogger().info("RESTART PASS: " + passed.size());
    }

    private void ceilingChecks() throws Exception {
        var environment = ((CraftWorld) nether).getHandle().paperConfig().environment;
        var original = environment.netherCeilingVoidDamageHeight;
        try {
            int x = 500;
            for (Axis axis : List.of(Axis.X, Axis.Z)) {
                environment.netherCeilingVoidDamageHeight = IntOr.Disabled.DISABLED;
                frame(nether, x, 130, 0, axis, 2, 3);
                frame(nether, x + 4, 64, 0, axis, 2, 3);
                frame(overworld, x * 8, 64, 0, axis, 2, 3);
                Object roof = portal(new Location(nether, x + 0.5, 130, 0.5));
                check(usable(roof, nether), axis + " roof portal is eligible while ceiling policy is disabled");
                ServerPlayer player = bot("Roof" + axis, UUID.randomUUID(), nether, x + 0.5, 130, 0.5);
                travel(player);
                check(player.level().getWorld().equals(overworld) && data(player) != null,
                        axis + " departure above the Nether roof saves a return pair");
                environment.netherCeilingVoidDamageHeight = new IntOr.Disabled(OptionalInt.of(130));
                check(!usable(roof, nether), axis + " portal at the live ceiling threshold is excluded");
                environment.netherCeilingVoidDamageHeight = new IntOr.Disabled(OptionalInt.of(131));
                check(usable(roof, nether), axis + " portal below the live ceiling threshold remains eligible");
                environment.netherCeilingVoidDamageHeight = new IntOr.Disabled(OptionalInt.of(128));
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
            environment.netherCeilingVoidDamageHeight = original;
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
                check(!dispatched
                        && Arrays.equals(userLink, data(user)) && Arrays.equals(neighborLink, data(neighbor)),
                        "ordinary player denied admin command " + action);
            }
            writeSettings(false, List.of(), false);
            check(!Bukkit.dispatchCommand(user.getBukkitEntity(), "portallinked reload"),
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
            Bukkit.dispatchCommand(operator, "portallinked status");
            String beforeInvalid = lastMessage(admin);
            byte[] savedBeforeInvalid = data(user).clone();
            for (String invalid : List.of(
                    "enabled: false\nexcluded-worlds: [world_nether\n",
                    "enabled: 'true'\n",
                    "debug: 'false'\n",
                    "excluded-worlds: world_nether\n",
                    "excluded-worlds: [42]\n",
                    "excluded-worlds: ['']\n")) {
                Files.writeString(subject.getDataFolder().toPath().resolve("config.yml"), invalid);
                Bukkit.dispatchCommand(operator, "portallinked reload");
                check(lastMessage(admin).startsWith("PortalLinked configuration could not be reloaded."),
                        "invalid configuration reports reload failure");
                Bukkit.dispatchCommand(operator, "portallinked status");
                check(lastMessage(admin).equals(beforeInvalid) && Arrays.equals(savedBeforeInvalid, data(user)),
                        "invalid YAML or setting type preserves active settings and saved links");
            }
            writeSettings(false, List.of(), false);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            check(lastMessage(admin).equals("PortalLinked configuration reloaded."),
                    "valid reload recovers after invalid configuration");
            for (World excluded : List.of(overworld, nether)) {
                writeSettings(true, List.of(excluded.getName()), false);
                Bukkit.dispatchCommand(operator, "portallinked reload");
                vanillaRoundTrip(user, "excluded world " + excluded.getName());
            }
            writeSettings(true, List.of(overworld.getName().toUpperCase(Locale.ROOT)), true);
            Bukkit.dispatchCommand(operator, "portallinked reload");
            travel(user);
            check(user.getX() >= 832 && user.getX() < 837 && lastPortal.getSearchRadius() == 0,
                    "world exclusions use exact case-sensitive names");
            check(logs.contains("Returning ConfigPlayer to their saved portal.")
                    && logs.contains("Saved portal link for ConfigPlayer."), "debug reload enables concise routing and recording logs");
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
        return messages.getOrDefault(player.getUUID(), List.of()).getLast();
    }

    private boolean vanillaLookup() {
        return vanillaOrigin.equals(lastPortal.getTo()) && vanillaRadius == lastPortal.getSearchRadius()
                && vanillaCreate == lastPortal.getCanCreatePortal();
    }

    private ServerPlayer bot(String name, UUID id, World world, double x, double y, double z) {
        GameProfile profile = new GameProfile(id, name);
        ServerLevel level = ((CraftWorld) world).getHandle();
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        connection.channel = new EmbeddedChannel();
        connection.address = new InetSocketAddress("127.0.0.1", 54321);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof ClientboundSystemChatPacket chat)
                    messages.computeIfAbsent(id, ignored -> new ArrayList<>()).add(chat.content().getString());
            }
            @Override public void send(Packet<?> packet, ChannelFutureListener listener) { send(packet); }
        };
        player.snapTo(x, y, z);
        player.getBukkitEntity().setInvulnerable(true);
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(id, player);
        try {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByName");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ServerPlayer> byName = (Map<String, ServerPlayer>) field.get(server.getPlayerList());
            byName.put(name.toLowerCase(Locale.ROOT), player);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot register native fixture player", ex);
        }
        level.addNewPlayer(player);
        bots.add(player);
        return player;
    }

    private TeleportTransition destination(ServerPlayer player) {
        lastPortal = null;
        return ((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalDestination(player.level(), player, player.blockPosition());
    }

    private void travel(ServerPlayer player) {
        TeleportTransition transition = destination(player);
        if (transition == null) throw new AssertionError("No portal transition from " + player.getBukkitEntity().getLocation());
        if (player.teleport(transition) == null) throw new AssertionError("Teleport failed");
    }

    private void relocate(ServerPlayer player, World world, double x, double y, double z) {
        TeleportTransition transition = new TeleportTransition(((CraftWorld) world).getHandle(), new Vec3(x, y, z),
                Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING).withCause(PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (player.teleport(transition) == null) throw new AssertionError("Relocation failed");
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
        int dx = axis == Axis.X ? 1 : 0, dz = axis == Axis.Z ? 1 : 0;
        for (int a = -1; a <= width; a++) for (int b = -1; b <= height; b++) {
            for (int side = -1; side <= 1; side++) {
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
