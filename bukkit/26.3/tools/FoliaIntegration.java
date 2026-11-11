// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Real async portal transitions; each operation runs on the owning region. */
public final class FoliaIntegration extends JavaPlugin implements Listener {
    private final List<String> checks = new ArrayList<>();
    private final NamespacedKey key = new NamespacedKey("portallinked", "last_crossing");
    private static final UUID PERSIST = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789003");
    private World overworld, nether;
    private boolean cancel;
    private UUID cancelNatural;
    private CompletableFuture<Boolean> foreignTeleport;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> start(), 5);
    }

    private void start() {
        overworld = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElseThrow();
        nether = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NETHER).findFirst().orElseThrow();
        CompletableFuture<Void> work = System.getProperty("portal.phase", "1").equals("2") ? restart() : mechanics();
        work.whenComplete((nothing, error) -> {
            try {
                Files.writeString(Path.of("result-" + System.getProperty("portal.phase", "1") + ".json"),
                        new GsonBuilder().serializeNulls().create().toJson(new Result(checks, error == null ? null : error.toString())));
                if (error != null) getLogger().log(java.util.logging.Level.SEVERE, "FOLIA TEST FAILURE", error);
            } catch (Exception ex) { throw new AssertionError(ex); }
            Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> Bukkit.shutdown(), 2);
        });
    }

    private CompletableFuture<Void> mechanics() {
        return cancellationRegression(false).thenCompose(v -> cancellationRegression(true))
                .thenCompose(v -> adjacentFrames())
                .thenCompose(v -> frame(overworld, 832, 64, 0, Axis.X, 4, 5))
                .thenCompose(v -> frame(overworld, 800, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> frame(nether, 100, 64, 0, Axis.Z, 5, 6))
                .thenCompose(v -> bot("FoliaOne", PERSIST, overworld, 832.5, 64, 0.5))
                .thenCompose(one -> bot("FoliaTwo", UUID.randomUUID(), overworld, 800.5, 64, 0.5).thenCompose(two ->
                    travel(one, nether, true).thenCompose(v -> travel(two, nether, true))
                    .thenCompose(v -> player(one, () -> {
                        check(data(one) != null, "native first journey records link on entity owner"); return null;
                    }))
                    .thenCompose(v -> travel(one, overworld, true))
                    .thenCompose(v -> player(one, () -> {
                        check(one.getX() >= 832 && one.getX() < 837, "Folia shared return reaches first player's original portal"); return null;
                    }))
                    .thenCompose(v -> blockedEntry(one))
                    .thenCompose(v -> travel(two, overworld, true))
                    .thenCompose(v -> player(two, () -> {
                        check(two.getX() >= 800 && two.getX() < 803, "Folia shared return reaches second player's own portal"); return null;
                    }))
                    .thenCompose(v -> travel(one, nether, true))
                    .thenCompose(v -> player(one, () -> { cancel = true; trigger(one); return null; }))
                    .thenCompose(v -> delay(one, 12))
                    .thenCompose(v -> player(one, () -> {
                        check(one.level().getWorld().equals(nether) && data(one) != null, "cancelled remembered return preserves player and link");
                        check(one.getBukkitEntity().getPortalCooldown() <= one.getDimensionChangingDelay(), "cancelled remembered return uses native player cooldown");
                        cancel = false; return null;
                    }))
                    .thenCompose(v -> region(overworld, 832, 0, () -> { overworld.getBlockAt(832, 64, 0).setType(Material.AIR, false); return null; }))
                    .thenCompose(v -> travel(one, overworld, true))
                    .thenCompose(v -> player(one, () -> {
                        check(one.getX() < 820, "destroyed return falls back to native Folia search"); return null;
                    }))
                    .thenCompose(v -> frame(overworld, 832, 64, 0, Axis.X, 4, 5))
                    .thenCompose(v -> relocate(one, new Location(overworld, 832.5, 64, 0.5)))
                    .thenCompose(v -> travel(one, nether, true))
                    .thenCompose(v -> player(one, () -> {
                        try { Files.write(Path.of("expected-pair.bin"), data(one)); }
                        catch (Exception ex) { throw new AssertionError(ex); }
                        check(data(one) != null, "Folia persistence pair established"); return null;
                    }))
                    .thenCompose(v -> configuration(one, two))));
    }

    private CompletableFuture<Void> blockedEntry(ServerPlayer probe) {
        return player(probe, () -> {
            byte[] before = data(probe);
            probe.level().getGameRules().set(GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS, false, null);
            trigger(probe);
            return before;
        }).thenCompose(before -> delay(probe, 4).thenCompose(v -> player(probe, () -> {
            check(probe.level().getWorld().equals(overworld), "remembered Folia return honors native Nether-entry gamerule");
            check(Arrays.equals(before, data(probe)), "denied Nether entry preserves existing saved pair");
            probe.level().getGameRules().set(GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS, true, null);
            return null;
        })));
    }

    private CompletableFuture<Void> cancellationRegression(boolean existingPair) {
        int source = existingPair ? 2080 : 1920;
        int destination = source / 8;
        return frame(overworld, source, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(nether, destination, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot(existingPair ? "CancelSaved" : "CancelFresh", UUID.randomUUID(), overworld, source + .5, 64, .5))
                .thenCompose(probe -> {
                    CompletableFuture<Void> seed = existingPair
                            ? travel(probe, nether, true).thenCompose(v -> relocate(probe, new Location(overworld, source + .5, 64, .5)))
                            : CompletableFuture.completedFuture(null);
                    return seed.thenCompose(v -> player(probe, () -> {
                        byte[] before = data(probe);
                        check(existingPair == (before != null), "cancellation probe starts with expected saved-pair state");
                        cancelNatural = probe.getUUID();
                        foreignTeleport = new CompletableFuture<>();
                        trigger(probe);
                        return before;
                    })).thenCompose(before -> foreignTeleport.thenCompose(success -> {
                        if (!success) throw new AssertionError("Independent PLUGIN teleport failed");
                        return delay(probe, 10).thenCompose(v -> player(probe, () -> {
                            check(probe.level().getWorld().equals(nether), "independent PLUGIN teleport reaches target portal");
                            check(Arrays.equals(before, data(probe)), existingPair
                                    ? "cancelled contact plus PLUGIN teleport preserves existing pair"
                                    : "cancelled contact plus PLUGIN teleport does not invent a pair");
                            cancelNatural = null;
                            return null;
                        }));
                    }));
                });
    }

    private CompletableFuture<Void> adjacentFrames() {
        return frame(overworld, 24000, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(overworld, 24000, 64, 1, Axis.X, 2, 3))
                .thenCompose(v -> frame(nether, 3000, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot("AdjacentFrames", UUID.randomUUID(), overworld, 24000.5, 64, 1.5))
                .thenCompose(probe -> travel(probe, nether, true).thenCompose(v -> travel(probe, overworld, true))
                        .thenCompose(v -> player(probe, () -> {
                            check(probe.getZ() >= 1 && probe.getZ() < 2, "adjacent parallel frames preserve exact contacted source frame");
                            return null;
                        })));
    }

    private CompletableFuture<Void> configuration(ServerPlayer one, ServerPlayer two) {
        return expanded(one).thenCompose(v -> player(one, () -> { one.getBukkitEntity().saveData(); return null; }))
                .thenCompose(v -> global(() -> {
                    try { Files.writeString(Path.of("plugins/PortalLinked/config.yml"), "enabled: null\nexcluded-worlds: []\ndebug: false\n"); }
                    catch (Exception ex) { throw new AssertionError(ex); }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked reload"); return null;
                }))
                .thenCompose(v -> travel(one, overworld, true))
                .thenCompose(v -> player(one, () -> {
                    check(one.getX() >= 832 && one.getX() < 837, "invalid Folia reload preserves active routing"); return null;
                }))
                .thenCompose(v -> travel(one, nether, true))
                .thenCompose(v -> global(() -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked forget FoliaTwo"); return null;
                }))
                .thenCompose(v -> delay(two, 4))
                .thenCompose(v -> player(two, () -> { check(data(two) == null, "console forget executes on selected player's owner region"); return null; }))
                .thenCompose(v -> player(one, () -> {
                    check(data(one) != null, "forget preserves another player's link");
                    try { Files.write(Path.of("expected-pair.bin"), data(one)); }
                    catch (Exception ex) { throw new AssertionError(ex); }
                    one.getBukkitEntity().saveData(); return null;
                }));
    }

    private CompletableFuture<Void> expanded(ServerPlayer one) {
        return player(one, () -> { trigger(one, false); return null; })
                .thenCompose(v -> delay(one, 2))
                .thenCompose(v -> player(one, () -> {
                    check(one.level().getWorld().equals(nether), "remembered return honors native portal delay before readiness"); return null;
                }))
                .thenCompose(v -> global(() -> { writeSettings(false, List.of()); return null; }))
                .thenCompose(v -> player(one, () -> data(one).clone()))
                .thenCompose(before -> travel(one, overworld, false).thenCompose(v -> travel(one, nether, false))
                    .thenCompose(v -> player(one, () -> { check(Arrays.equals(before, data(one)), "disabled Folia routing retains links across native round trip"); return null; })))
                .thenCompose(v -> global(() -> { writeSettings(true, List.of(nether.getName())); return null; }))
                .thenCompose(v -> player(one, () -> data(one).clone()))
                .thenCompose(before -> travel(one, overworld, false).thenCompose(v -> travel(one, nether, false))
                    .thenCompose(v -> player(one, () -> { check(Arrays.equals(before, data(one)), "Folia exclusion skips both directions and preserves links"); return null; })))
                .thenCompose(v -> global(() -> { writeSettings(true, List.of()); return null; }))
                .thenCompose(v -> frame(overworld, 10000, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> frame(overworld, 15000, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> frame(nether, 1250, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> frame(nether, 1875, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> {
                    CompletableFuture<Void> a = bot("FoliaFarA", UUID.randomUUID(), overworld, 10000.5, 64, 0.5)
                            .thenCompose(player -> travel(player, nether, true).thenCompose(n -> travel(player, overworld, true))
                                    .thenCompose(n -> player(player, () -> {
                                        check(Math.abs(player.getX() - 10000.5) < 2, "first distant region completes independent concurrent round trip"); return null;
                                    })));
                    CompletableFuture<Void> b = bot("FoliaFarB", UUID.randomUUID(), overworld, 15000.5, 64, 0.5)
                            .thenCompose(player -> travel(player, nether, true).thenCompose(n -> travel(player, overworld, true))
                                    .thenCompose(n -> player(player, () -> {
                                        check(Math.abs(player.getX() - 15000.5) < 2, "second distant region completes independent concurrent round trip"); return null;
                                    })));
                    return CompletableFuture.allOf(a, b);
                });
    }

    private void writeSettings(boolean enabled, List<String> excluded) {
        try {
            Files.writeString(Path.of("plugins/PortalLinked/config.yml"),
                    "enabled: " + enabled + "\nexcluded-worlds: " + new Gson().toJson(excluded) + "\ndebug: false\n");
        } catch (Exception ex) { throw new AssertionError(ex); }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked reload");
    }

    private CompletableFuture<Void> restart() {
        return bot("FoliaOne", PERSIST, nether, 100.5, 64, 0.5).thenCompose(one -> player(one, () -> {
            one.getBukkitEntity().loadData();
            try { check(Arrays.equals(data(one), Files.readAllBytes(Path.of("expected-pair.bin"))), "Folia player data survives actual server restart"); }
            catch (Exception ex) { throw new AssertionError(ex); }
            return null;
        }).thenCompose(v -> global(() -> {
            try { Files.writeString(Path.of("plugins/PortalLinked/config.yml"), "enabled: true\nexcluded-worlds: []\ndebug: false\n"); }
            catch (Exception ex) { throw new AssertionError(ex); }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked reload"); return null;
        })).thenCompose(v -> travel(one, overworld, true)).thenCompose(v -> player(one, () -> {
            check(one.getX() >= 832 && one.getX() < 837, "Folia remembered return works after restart"); return null;
        })));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void cancel(PlayerPortalEvent event) { if (cancel) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void foreignRedirect(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.getUniqueId().equals(cancelNatural)) return;
        event.setCancelled(true);
        double x = player.getLocation().getX() / 8;
        player.teleportAsync(new Location(nether, x, 64, .5), PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((success, error) -> {
                    if (error != null) foreignTeleport.completeExceptionally(error);
                    else foreignTeleport.complete(success);
                });
    }

    private CompletableFuture<Void> travel(ServerPlayer player, World target, boolean requireLink) {
        return player(player, () -> { byte[] before = data(player); trigger(player); return before; })
                .thenCompose(before -> arrived(player, target, requireLink, before, 0));
    }

    private CompletableFuture<Void> arrived(ServerPlayer nativePlayer, World target, boolean requireLink, byte[] before, int attempts) {
        return delay(nativePlayer, 1).thenCompose(v -> player(nativePlayer, () -> {
                if (!nativePlayer.level().getWorld().equals(target) && nativePlayer.getBukkitEntity().getPortalCooldown() == 0)
                    trigger(nativePlayer);
                return nativePlayer.level().getWorld().equals(target)
                        && (!requireLink || (data(nativePlayer) != null && !Arrays.equals(before, data(nativePlayer))));
            }))
                .thenCompose(done -> {
                    if (done) return CompletableFuture.completedFuture(null);
                    if (attempts > 400) return CompletableFuture.failedFuture(new AssertionError("Folia async trip did not arrive at " + target.getName()));
                    return arrived(nativePlayer, target, requireLink, before, attempts + 1);
                });
    }

    private void trigger(ServerPlayer player) { trigger(player, true); }

    private void trigger(ServerPlayer player, boolean ready) {
        try {
            player.getBukkitEntity().setPortalCooldown(0);
            NetherPortalBlock block = (NetherPortalBlock) Blocks.NETHER_PORTAL;
            PortalProcessor process = new PortalProcessor(block, player.blockPosition());
            var time = PortalProcessor.class.getDeclaredField("portalTime"); time.setAccessible(true);
            time.setInt(process, ready ? block.getPortalTransitionTime(player.level(), player) : 0);
            process.setAsInsidePortalThisTick(false);
            var field = Entity.class.getDeclaredField("portalProcess"); field.setAccessible(true); field.set(player, process);
            var inside = NetherPortalBlock.class.getDeclaredMethod("entityInside", BlockState.class, Level.class,
                    BlockPos.class, Entity.class, InsideBlockEffectApplier.class, boolean.class);
            inside.setAccessible(true);
            inside.invoke(block, player.level().getBlockState(player.blockPosition()), player.level(), player.blockPosition(),
                    player, InsideBlockEffectApplier.NOOP, true);
            var handle = Entity.class.getDeclaredMethod("handlePortal"); handle.setAccessible(true); handle.invoke(player);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private CompletableFuture<ServerPlayer> bot(String name, UUID id, World world, double x, double y, double z) {
        return region(world, (int) x, (int) z, () -> {
            try {
                ServerLevel level = (ServerLevel) world.getClass().getMethod("getHandle").invoke(world);
                MinecraftServer server = level.getServer();
                GameProfile profile = new GameProfile(id, name);
                ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
                Connection connection = new Connection(PacketFlow.SERVERBOUND);
                connection.channel = new EmbeddedChannel(); connection.address = new InetSocketAddress("127.0.0.1", 54321);
                player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
                    @Override public void send(Packet<?> packet) {}
                    @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
                };
                var listener = Connection.class.getDeclaredField("packetListener"); listener.setAccessible(true);
                listener.set(connection, player.connection);
                player.snapTo(x, y, z); player.setPermanentlyInvulnerable(true); player.setNoGravity(true);
                var listField = net.minecraft.server.players.PlayerList.class.getDeclaredField("players"); listField.setAccessible(true);
                @SuppressWarnings("unchecked") List<ServerPlayer> list = (List<ServerPlayer>) listField.get(server.getPlayerList()); list.add(player);
                var uuidField = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID"); uuidField.setAccessible(true);
                @SuppressWarnings("unchecked") Map<UUID, ServerPlayer> ids = (Map<UUID, ServerPlayer>) uuidField.get(server.getPlayerList()); ids.put(id, player);
                var nameField = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByName"); nameField.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, ServerPlayer> names = (Map<String, ServerPlayer>) nameField.get(server.getPlayerList()); names.put(name.toLowerCase(Locale.ROOT), player);
                player.getBukkitEntity().setPortalCooldown(300);
                level.addNewPlayer(player);
                level.getCurrentWorldData().addConnection(player);
                return player;
            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        });
    }

    private CompletableFuture<Void> frame(World world, int x, int y, int z, Axis axis, int width, int height) {
        return region(world, x, z, () -> {
            Orientable portal = (Orientable) Bukkit.createBlockData(Material.NETHER_PORTAL); portal.setAxis(axis);
            for (int row = -1; row <= height; row++) for (int col = -1; col <= width; col++) {
                var block = world.getBlockAt(x + (axis == Axis.X ? col : 0), y + row, z + (axis == Axis.Z ? col : 0));
                if (row == -1 || row == height || col == -1 || col == width) block.setType(Material.OBSIDIAN, false);
                else block.setBlockData(portal, false);
            }
            return null;
        });
    }

    private <T> CompletableFuture<T> region(World world, int x, int z, Supplier<T> action) {
        List<CompletableFuture<?>> chunks = new ArrayList<>();
        for (int cx = (x >> 4) - 2; cx <= (x >> 4) + 2; cx++) for (int cz = (z >> 4) - 2; cz <= (z >> 4) + 2; cz++)
            chunks.add(world.getChunkAtAsync(cx, cz, true));
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenCompose(v -> {
            CompletableFuture<T> result = new CompletableFuture<>();
            Bukkit.getRegionScheduler().execute(this, world, x >> 4, z >> 4, () -> complete(result, action));
            return result;
        });
    }

    private <T> CompletableFuture<T> player(ServerPlayer player, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        player.getBukkitEntity().getScheduler().run(this, task -> complete(result, action),
                () -> result.completeExceptionally(new AssertionError("Fixture player retired")));
        return result;
    }

    private CompletableFuture<Void> delay(ServerPlayer player, long ticks) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        player.getBukkitEntity().getScheduler().runDelayed(this, task -> result.complete(null),
                () -> result.completeExceptionally(new AssertionError("Fixture player retired")), ticks);
        return result;
    }

    private <T> CompletableFuture<T> global(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this, () -> complete(result, action)); return result;
    }

    private CompletableFuture<Void> relocate(ServerPlayer player, Location destination) {
        return player(player, () -> player.getBukkitEntity().teleportAsync(destination)).thenCompose(future -> future).thenApply(success -> {
            if (!success) throw new AssertionError("Fixture relocation failed"); return null;
        });
    }

    private <T> void complete(CompletableFuture<T> result, Supplier<T> action) {
        try { result.complete(action.get()); } catch (Throwable ex) { result.completeExceptionally(ex); }
    }

    private byte[] data(ServerPlayer player) { return player.getBukkitEntity().getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY); }
    private synchronized void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); checks.add(text); getLogger().info("PASS " + text); }
    private record Result(List<String> checks, String failure) {}
}
