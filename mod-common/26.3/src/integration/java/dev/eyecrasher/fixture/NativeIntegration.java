// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import dev.eyecrasher.portallinked.NativePortal;
import dev.eyecrasher.portallinked.PlayerLinkData;
import dev.eyecrasher.portallinked.PortalPair;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Compiled only into the disposable fixture JAR, never the released mod. */
public final class NativeIntegration {
    public static UUID cancelTeleport;
    private static boolean ran;
    private static final Gson JSON = new Gson();
    private static final UUID PERSIST = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789002");
    private final List<String> checks = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final MinecraftServer server;
    private final ServerLevel overworld, nether;

    private NativeIntegration(MinecraftServer server) {
        this.server = server; overworld = server.getLevel(Level.OVERWORLD); nether = server.getLevel(Level.NETHER);
    }

    public static void run(MinecraftServer server) {
        if (ran) return;
        ran = true;
        NativeIntegration test = new NativeIntegration(server);
        String failure = null;
        try {
            if (System.getProperty("portallinked.phase", "1").equals("2")) test.restart();
            else { test.mechanics(); test.administration(); test.persistence(); }
            server.saveEverything(true, true, true);
        } catch (Throwable ex) { ex.printStackTrace(); failure = ex.toString(); }
        try {
            Files.writeString(Path.of("result-" + System.getProperty("portallinked.phase", "1") + ".json"),
                    JSON.toJson(new Outcome(test.checks, failure, test.replies)));
        } catch (Exception ex) { throw new AssertionError(ex); }
        server.halt(false);
    }

    private void mechanics() throws Exception {
        adjacentArrival(Direction.Axis.X);
        adjacentArrival(Direction.Axis.Z);
        frame(overworld, 832, 64, 0, Direction.Axis.X, 4, 5);
        frame(overworld, 800, 64, 0, Direction.Axis.X, 2, 3);
        frame(nether, 100, 64, 0, Direction.Axis.Z, 5, 6);
        NativePortal large = NativePortal.at(overworld, new BlockPos(832, 64, 0)).orElseThrow();
        check(large.equals(NativePortal.at(overworld, new BlockPos(835, 68, 0)).orElseThrow()), "whole frame identity");
        frame(overworld, -64, 80, -32, Direction.Axis.X, 21, 21);
        check(NativePortal.at(overworld, new BlockPos(-44, 100, -32)).orElseThrow().width() == 21, "maximum portal and negative coordinates");
        ServerPlayer vanilla = bot("VanillaProbe", UUID.randomUUID(), nether, 100.5, 64, 0.5);
        travel(vanilla);
        check(vanilla.level() == overworld && vanilla.getX() < 820, "vanilla control chooses neighboring base");
        ServerPlayer one = bot("PortalOne", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        ServerPlayer two = bot("PortalTwo", UUID.randomUUID(), overworld, 800.5, 64, 0.5);
        travel(one); travel(two);
        check(one.level() == nether && two.level() == nether, "both players reach shared Nether portal");
        check(pair(one) != null && pair(two) != null && pair(one).arrival().equals(pair(two).arrival()), "successful journeys save per-player pairs");
        relocate(one, nether, 100.5, 65, 4.4);
        travel(one); travel(two);
        check(one.level() == overworld && one.getX() >= 832 && one.getX() < 837, "remembered return fixes neighboring-portal case");
        check(two.level() == overworld && two.getX() >= 800 && two.getX() < 803, "shared portal keeps second player's own destination");
        PortalPair before = pair(one);
        cancelTeleport = one.getUUID();
        TeleportTransition cancelled = destination(one);
        check(one.teleport(cancelled) == null && one.level() == overworld && before.equals(pair(one)), "cancelled actual teleport preserves pair");
        cancelTeleport = null;
        travel(one);
        overworld.setBlock(new BlockPos(832, 64, 0), Blocks.AIR.defaultBlockState(), 2);
        travel(one);
        check(one.level() == overworld && one.getX() < 820, "destroyed saved portal falls back to vanilla neighbor");
        frame(overworld, 832, 64, 0, Direction.Axis.X, 4, 5);
        relocate(one, overworld, 832.5, 64, 0.5); travel(one);
        double originalBorder = overworld.getWorldBorder().getSize();
        overworld.getWorldBorder().setSize(1640);
        travel(one);
        check(one.level() == overworld && one.getX() < 820, "border-excluded return uses vanilla lookup");
        overworld.getWorldBorder().setSize(originalBorder);
        frame(nether, 1000, 64, 0, Direction.Axis.X, 2, 3);
        relocate(one, nether, 1000.5, 64, 0.5); travel(one);
        check(one.level() == overworld && Math.abs(one.getX() - 8000) < 140, "new Nether route retains 1:8 scaling and exit creation");
        check(NativePortal.touching(one).isPresent() && pair(one).departure().x() == 1000, "generated exit establishes new pair");
        PortalPair saved = pair(one);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        one.saveWithoutId(output);
        ServerPlayer clone = make("PortalOne", one.getUUID(), overworld, one.getX(), one.getY(), one.getZ());
        clone.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), output.buildResult()));
        check(saved.equals(pair(clone)), "saved player NBT round-trips through native load");
        clone.restoreFrom(one, false);
        check(saved.equals(pair(clone)), "saved pair survives native respawn copy");
        CompoundTag corrupt = output.buildResult().copy();
        corrupt.getCompound(PortalPair.KEY).orElseThrow().putInt("version", 127);
        clone.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), corrupt));
        check(pair(clone) == null, "unsupported persisted version falls back without a link");
        ServerPlayer endPlayer = bot("EndProbe", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        travel(endPlayer); relocate(endPlayer, overworld, 832.5, 64, 0.5);
        PortalPair endSaved = pair(endPlayer);
        TeleportTransition end = ((EndPortalBlock) Blocks.END_PORTAL).getPortalDestination(overworld, endPlayer, endPlayer.blockPosition());
        check(end != null && endPlayer.teleport(end) != null && endPlayer.level() == server.getLevel(Level.END)
                && endSaved.equals(pair(endPlayer)), "actual End entrance leaves Nether pair unchanged");
        Pig pig = new Pig(EntityTypes.PIG, nether); pig.snapTo(100.5, 64, 0.5); nether.addFreshEntity(pig);
        Entity movedPig = pig.teleport(((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalDestination(nether, pig, pig.blockPosition()));
        check(movedPig != null && movedPig.level() == overworld, "actual mob portal travel remains vanilla");
        ItemEntity item = new ItemEntity(nether, 100.5, 64, 0.5, new ItemStack(Items.DIAMOND, 3)); nether.addFreshEntity(item);
        Entity movedItem = item.teleport(((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalDestination(nether, item, item.blockPosition()));
        check(movedItem instanceof ItemEntity diamonds && diamonds.level() == overworld && diamonds.getItem().getCount() == 3,
                "actual item portal travel preserves items");
    }

    private void adjacentArrival(Direction.Axis axis) throws Exception {
        boolean x = axis == Direction.Axis.X;
        int base = x ? 120032 : 128032, target = base / 8 - 4;
        frame(overworld, x ? base : 8, 64, x ? 8 : base, axis, 2, 3);
        frame(nether, x ? target : 0, 64, x ? 0 : target, axis, 2, 3);
        frame(nether, x ? target : 1, 64, x ? 1 : target, axis, 2, 3);
        ServerPlayer player = bot("Arrival" + axis.name(), UUID.randomUUID(), overworld, x ? base + .5 : 8.1,
                64, x ? 8.1 : base + .5);
        travel(player);
        check(player.level() == nether && (x ? player.getZ() : player.getX()) >= 1
                && (x ? player.getZ() : player.getX()) < 2, "native exit selects adjacent plane 1 on axis " + axis);
        check((x ? pair(player).arrival().z() : pair(player).arrival().x()) == 1,
                "arrival identity follows feet instead of overlapping neighboring frame on axis " + axis);
        relocate(player, nether, x ? target + .5 : 1.5, 64, x ? 1.5 : target + .5);
        travel(player);
        check(player.level() == overworld && Math.abs((x ? player.getX() : player.getZ()) - base) < 3,
                "centered adjacent arrival returns to original base on axis " + axis);
    }

    private void administration() throws Exception {
        ServerPlayer user = bot("ConfigPlayer", UUID.randomUUID(), overworld, 832.5, 64, 0.5);
        ServerPlayer neighbor = bot("ConfigNeighbor", UUID.randomUUID(), overworld, 800.5, 64, 0.5);
        travel(user); travel(neighbor);
        try {
            command("portallinked forget ConfigNeighbor", user, false);
            throw new AssertionError("ordinary player was allowed an admin command");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException denied) {
            check(pair(user) != null && pair(neighbor) != null, "ordinary player cannot use admin commands");
        }
        command("portallinked status", user, true);
        check(last().equals("PortalLinked: enabled=true, excluded-worlds=[], debug=false."), "admin status reports defaults");
        for (String excluded : List.of("", "minecraft:overworld", "minecraft:the_nether")) {
            writeConfig(false, excluded, false); command("portallinked reload", user, true);
            PortalPair before = pair(user);
            travel(user); travel(user);
            check(before.equals(pair(user)), "disabled mode preserves stored link with exclusions " + excluded);
            for (String invalid : List.of("{", "{\"enabled\":null,\"excluded-worlds\":[],\"debug\":false}",
                    "{\"excluded-worlds\":[],\"debug\":false}", "{\"enabled\":true,\"excluded-worlds\":null,\"debug\":false}",
                    "{\"enabled\":true,\"excluded-worlds\":[],\"debug\":null}",
                    "{\"enabled\":\"true\",\"excluded-worlds\":[],\"debug\":false}")) {
                command("portallinked status", user, true); String status = last();
                Files.writeString(Path.of("config/portallinked.json"), invalid);
                check(command("portallinked reload", user, true) == 0 && last().contains("Previous settings are unchanged"), "invalid reload is reported");
                command("portallinked status", user, true);
                check(last().equals(status) && before.equals(pair(user)), "invalid reload preserves active settings and data");
            }
            writeConfig(true, excluded, false); command("portallinked reload", user, true);
            if (!excluded.isEmpty()) {
                travel(user); travel(user);
                check(before.equals(pair(user)), "either excluded endpoint skips recording " + excluded);
            }
        }
        writeConfig(true, "", false); command("portallinked reload", user, true);
        PortalPair own = pair(user);
        command("portallinked forget ConfigNeighbor", user, true);
        check(pair(neighbor) == null && own.equals(pair(user)), "forget changes only selected player");
        command("portallinked forget", user, true);
        check(pair(user) == null, "self forget clears saved pair");
    }

    private void persistence() throws Exception {
        frame(overworld, 832, 64, 0, Direction.Axis.X, 4, 5);
        frame(nether, 100, 64, 0, Direction.Axis.Z, 5, 6);
        ServerPlayer player = bot("PersistProbe", PERSIST, overworld, 832.5, 64, 0.5);
        travel(player);
        check(pair(player) != null, "persistence fixture establishes saved pair");
        Files.writeString(Path.of("expected-pair.json"), JSON.toJson(pair(player)));
    }

    private void restart() throws Exception {
        ServerPlayer player = bot("PersistProbe", PERSIST, nether, 100.5, 64, 0.5);
        CompoundTag tag = server.getPlayerList().loadPlayerData(new NameAndId(PERSIST, "PersistProbe")).orElseThrow();
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
        check(JSON.toJson(pair(player)).equals(Files.readString(Path.of("expected-pair.json"))), "pair survives actual server shutdown and restart");
        command("portallinked status", player, true);
        check(last().equals("PortalLinked: enabled=false, excluded-worlds=[], debug=false."), "invalid startup keeps linking disabled");
        PortalPair before = pair(player);
        travel(player); travel(player);
        check(before.equals(pair(player)), "invalid startup retains stored link");
        writeConfig(true, "", false); command("portallinked reload", player, true);
        travel(player);
        check(player.level() == overworld && player.getX() >= 832 && player.getX() < 837, "valid reload restores remembered return after restart");
    }

    private PortalPair pair(ServerPlayer player) { return ((PlayerLinkData) player).portallinked$getPair(); }
    private TeleportTransition destination(ServerPlayer player) {
        return ((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalDestination(player.level(), player, player.blockPosition());
    }
    private void travel(ServerPlayer player) {
        TeleportTransition transition = destination(player);
        if (transition == null || player.teleport(transition) == null) throw new AssertionError("Native portal transition failed for " + player.getScoreboardName());
    }
    private void relocate(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        if (player.teleport(new TeleportTransition(level, new Vec3(x, y, z), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING)) == null)
            throw new AssertionError("Native relocation failed");
    }
    private void frame(ServerLevel level, int x, int y, int z, Direction.Axis axis, int width, int height) {
        int dx = axis == Direction.Axis.X ? 1 : 0, dz = axis == Direction.Axis.Z ? 1 : 0;
        for (int row = -1; row <= height; row++) for (int col = -1; col <= width; col++) {
            boolean edge = row == -1 || row == height || col == -1 || col == width;
            level.setBlock(new BlockPos(x + dx * col, y + row, z + dz * col),
                    edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis), 18);
        }
    }
    private ServerPlayer make(String name, UUID id, ServerLevel level, double x, double y, double z) throws Exception {
        GameProfile profile = new GameProfile(id, name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        field(Connection.class, connection, "channel", new EmbeddedChannel());
        field(Connection.class, connection, "address", new InetSocketAddress("127.0.0.1", 54321));
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {}
        };
        player.snapTo(x, y, z); player.setPermanentlyInvulnerable(true);
        return player;
    }
    private ServerPlayer bot(String name, UUID id, ServerLevel level, double x, double y, double z) throws Exception {
        ServerPlayer player = make(name, id, level, x, y, z);
        var players = net.minecraft.server.players.PlayerList.class.getDeclaredField("players"); players.setAccessible(true);
        @SuppressWarnings("unchecked") List<ServerPlayer> registered = (List<ServerPlayer>) players.get(server.getPlayerList());
        registered.add(player);
        var ids = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID"); ids.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, ServerPlayer> byId = (Map<UUID, ServerPlayer>) ids.get(server.getPlayerList());
        byId.put(id, player); level.addNewPlayer(player);
        return player;
    }
    private void field(Class<?> owner, Object object, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private int command(String text, ServerPlayer player, boolean admin) throws Exception {
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { replies.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack source = new CommandSourceStack(capture, player.position(), player.getRotationVector(), player.level(),
                admin ? server.createCommandSourceStack().permissions() : player.permissions(), server, player);
        return server.getCommands().getDispatcher().execute(text, source);
    }
    private String last() { return replies.getLast(); }
    private void writeConfig(boolean enabled, String excluded, boolean debug) throws Exception {
        Files.writeString(Path.of("config/portallinked.json"), JSON.toJson(Map.of("enabled", enabled,
                "excluded-worlds", excluded.isEmpty() ? List.of() : List.of(excluded), "debug", debug)));
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks.add(message); }
    private record Outcome(List<String> checks, String failure, List<String> replies) {}
}
