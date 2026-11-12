// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture;

import com.google.gson.Gson;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Axis;
import org.bukkit.attribute.Attribute;
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
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import io.papermc.paper.threadedregions.RegionizedServer;

/** Real async portal transitions; each operation runs on the owning region. */
public final class FoliaIntegration extends JavaPlugin implements Listener {
    private final List<String> checks = new ArrayList<>();
    private final NamespacedKey key = new NamespacedKey("portallinked", "last_crossing");
    private static final UUID PERSIST = UUID.fromString("731e37df-d67b-4bf2-a9bc-123456789003");
    private World overworld, nether;
    private boolean cancel;
    private UUID cancelNatural;
    private CompletableFuture<Boolean> foreignTeleport;
    private UUID cancelReturn;
    private UUID cancelPending;
    private boolean cancelPendingEarly;
    private int pendingCancellations;
    private UUID reloadOnRemoval;
    private CompletableFuture<Boolean> removalReload;
    private final Map<UUID, Runnable> afterArrival = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> portalSounds = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> portalTickets = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Location>> moveOnArrival = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> start(), 5);
    }

    private void start() {
        overworld = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElseThrow();
        nether = Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NETHER).findFirst().orElseThrow();
        CompletableFuture<Void> work = switch (System.getProperty("portallinked.scenario", "")) {
            case "pending-contact" -> pendingContacts();
            case "blocked-return" -> blockedReturns();
            case "teleport-lock" -> teleportLock().thenCompose(v -> reloadRace()).thenCompose(v -> portalEffects())
                    .thenCompose(v -> immediateLogout(true));
            case "source-exit" -> sourceExits().thenCompose(v -> pendingContacts()).thenCompose(v -> cancelledReturnContact())
                    .thenCompose(v -> teleportLock()).thenCompose(v -> movementRegression())
                    .thenCompose(v -> scaledContact(Axis.X)).thenCompose(v -> scaledContact(Axis.Z))
                    .thenCompose(v -> immediateLogout(true));
            case "departure-contact" -> departureContacts().thenCompose(v -> adjacentArrival(Axis.X))
                    .thenCompose(v -> adjacentArrival(Axis.Z));
            default -> System.getProperty("portal.phase", "1").equals("2") ? restart() : mechanics();
        };
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
        return sourceExits().thenCompose(v -> departureContacts())
                .thenCompose(v -> pendingContacts()).thenCompose(v -> blockedReturns()).thenCompose(v -> teleportLock())
                .thenCompose(v -> cancellationRegression(false)).thenCompose(v -> cancellationRegression(true))
                .thenCompose(v -> adjacentFrames())
                .thenCompose(v -> movementRegression())
                .thenCompose(v -> reloadRace())
                .thenCompose(v -> cancelledReturnContact())
                .thenCompose(v -> adjacentArrival(Axis.X))
                .thenCompose(v -> adjacentArrival(Axis.Z))
                .thenCompose(v -> scaledContact(Axis.X))
                .thenCompose(v -> scaledContact(Axis.Z))
                .thenCompose(v -> immediateLogout(false))
                .thenCompose(v -> immediateLogout(true))
                .thenCompose(v -> portalEffects())
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

    private CompletableFuture<Void> pendingContacts() {
        return pendingContact(true, true).thenCompose(v -> pendingContact(true, false))
                .thenCompose(v -> pendingContact(false, true)).thenCompose(v -> pendingContact(false, false));
    }

    private CompletableFuture<Void> sourceExits() {
        return sourceExit(false, false).thenCompose(v -> sourceExit(true, false))
                .thenCompose(v -> sourceExit(true, true));
    }

    private CompletableFuture<Void> sourceExit(boolean reenter, boolean creative) {
        int base = creative ? 184032 : reenter ? 176032 : 168032, target = base / 8;
        String label = "reenter=" + reenter + " creative=" + creative;
        return frame(overworld, base, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(nether, target, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> bot("SourceExit", UUID.randomUUID(), overworld, base + .5, 64, .5))
                .thenCompose(probe -> travel(probe, nether, true).thenCompose(v -> {
                    CompletableFuture<Void> entered = new CompletableFuture<>(), release = new CompletableFuture<>(), finished = new CompletableFuture<>();
                    Bukkit.getRegionScheduler().execute(this, overworld, base >> 4, 0, () -> {
                        entered.complete(null);
                        try { release.get(8, TimeUnit.SECONDS); finished.complete(null); }
                        catch (Exception ex) { finished.completeExceptionally(ex); }
                    });
                    int[] exitedTick = new int[1];
                    return entered.thenCompose(done -> player(probe, () -> {
                        probe.getBukkitEntity().setGameMode(creative ? org.bukkit.GameMode.CREATIVE : org.bukkit.GameMode.SURVIVAL);
                        int nativeDelay = ((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalTransitionTime(probe.level(), probe);
                        check(nativeDelay == (creative ? 0 : 80), "source-exit probe uses actual native delay: " + label);
                        byte[] before = data(probe);
                        trigger(probe);
                        exitedTick[0] = probe.getBukkitEntity().getTicksLived();
                        probe.snapTo(target + .5, 64, 4.5);
                        return before;
                    })).thenCompose(before -> outsideTicks(probe, 24).thenCompose(done -> player(probe, () -> {
                        check(!finished.isDone(), "destination-region validation stays blocked across source exit: " + label);
                        check(probe.getBukkitEntity().getTicksLived() - exitedTick[0] >= 24 && process(probe) == null,
                                "native progress expires after more than twenty actual source ticks: " + label);
                        if (reenter) {
                            probe.snapTo(target + .5, 64, .5);
                            inside(probe, probe.blockPosition());
                            handlePortal(probe);
                            if (!creative) check(process(probe) != null && process(probe).getPortalTime() <= 1,
                                    "reentry starts with fresh native delay instead of stale held progress: " + label);
                        }
                        release.complete(null);
                        return null;
                    })).thenCompose(done -> finished).thenCompose(done -> delay(probe, 4)).thenCompose(done -> player(probe, () -> {
                        if (!creative) {
                            check(probe.level().getWorld().equals(nether) && Arrays.equals(before, data(probe)),
                                    "released old validation cannot launch or mutate an exited journey: " + label);
                        }
                        return null;
                    })).thenCompose(done -> reenter && creative ? arrived(probe, overworld, true, before, 0)
                            : player(probe, () -> { probe.snapTo(target + .5, 64, .5); return null; })
                                    .thenCompose(ignored -> travel(probe, overworld, true)))
                            .thenCompose(done -> this.<Void>player(probe, () -> {
                                check(probe.level().getWorld().equals(overworld), "ordinary saved return succeeds after source release: " + label);
                                return null;
                            }))).whenComplete((done, error) -> release.complete(null));
                }));
    }

    private CompletableFuture<Void> outsideTicks(ServerPlayer player, int remaining) {
        if (remaining == 0) return CompletableFuture.completedFuture(null);
        return delay(player, 1).thenCompose(v -> player(player, () -> { handlePortal(player); return null; }))
                .thenCompose(v -> outsideTicks(player, remaining - 1));
    }

    private CompletableFuture<Void> departureContacts() {
        return departureContact(Axis.X, false).thenCompose(v -> departureContact(Axis.Z, false))
                .thenCompose(v -> departureContact(Axis.X, true)).thenCompose(v -> departureContact(Axis.Z, true));
    }

    private CompletableFuture<Void> departureContact(Axis axis, boolean homeNether) {
        boolean x = axis == Axis.X;
        World home = homeNether ? nether : overworld, shared = homeNether ? overworld : nether;
        int base = homeNether ? 24004 : 192032, target = homeNether ? base * 8 : base / 8;
        String label = "axis=" + axis + " homeNether=" + homeNether;
        return clearForScale(home, axis, base - 3, base + 7)
                .thenCompose(v -> clearForScale(shared, axis, target - 3, target + 7))
                .thenCompose(v -> frame(home, x ? base : 0, 64, x ? 0 : base, axis, 4, 8))
                .thenCompose(v -> frame(home, x ? base : 1, 64, x ? 1 : base, axis, 4, 8))
                .thenCompose(v -> frame(shared, x ? target : 0, 64, x ? 0 : target, axis, 4, 8))
                .thenCompose(v -> bot("Departure" + axis + homeNether, UUID.randomUUID(), home,
                        x ? base + 2 : .9, 64, x ? .9 : base + 2))
                .thenCompose(probe -> player(probe, () -> {
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
                    contact(probe, true, first); inside(probe, second);
                    check(process(probe).getEntryPosition().equals(first), "native processor retains first accepted departure: " + label);
                    byte[] before = data(probe); handlePortal(probe); return before;
                }).thenCompose(before -> arrived(probe, shared, true, before, 0)).thenCompose(v -> player(probe, () -> {
                    check(java.nio.ByteBuffer.wrap(data(probe)).getInt(x ? 25 : 17) == 0,
                            "saved departure matches native first contact: " + label); return null;
                })).thenCompose(v -> travel(probe, home, true)).thenCompose(v -> player(probe, () -> {
                    check(Math.abs((x ? probe.getZ() : probe.getX()) - .5) < .1,
                            "remembered round trip reaches native departure plane: " + label); return null;
                })));
    }

    private CompletableFuture<Void> pendingContact(boolean saved, boolean early) {
        int base = 88032 + (saved ? 0 : 16000) + (early ? 0 : 8000), target = base / 8 - 4;
        String label = "saved=" + saved + " early=" + early;
        return frame(overworld, base, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(nether, target, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> bot("PendingProbe", UUID.randomUUID(), overworld, base + .5, 64, .5))
                .thenCompose(probe -> (saved ? travel(probe, nether, true) : CompletableFuture.<Void>completedFuture(null))
                        .thenCompose(v -> player(probe, () -> {
                            byte[] before = data(probe);
                            check(saved == (before != null), "pending-contact probe has expected pair: " + label);
                            if (saved) trigger(probe); else contact(probe, true, probe.blockPosition());
                            cancelPending = probe.getUUID(); cancelPendingEarly = early; pendingCancellations = 0;
                            trigger(probe);
                            check(pendingCancellations == 1, "foreign listener cancels the subsequent native contact: " + label);
                            return before;
                        })).thenCompose(before -> (saved ? delay(probe, 8)
                                : relocate(probe, new Location(nether, target + .5, 64, .5)))
                                .thenCompose(v -> player(probe, () -> {
                                    check(probe.level().getWorld().equals(nether), "subsequent cancellation prevents pending saved return: " + label);
                                    check(Arrays.equals(before, data(probe)), "subsequent cancellation prevents stale pair recording: " + label);
                                    cancelPending = null; return null;
                                })))
                        .thenCompose(v -> saved ? travel(probe, overworld, true) : CompletableFuture.completedFuture(null)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void cancelPendingEarly(EntityPortalEnterEvent event) {
        if (cancelPendingEarly && event.getEntity().getUniqueId().equals(cancelPending)) {
            pendingCancellations++; event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void cancelPendingLate(EntityPortalEnterEvent event) {
        if (!cancelPendingEarly && event.getEntity().getUniqueId().equals(cancelPending)) {
            pendingCancellations++; event.setCancelled(true);
        }
    }

    private CompletableFuture<Void> teleportLock() {
        return frame(overworld, 80032, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(nether, 10000, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> bot("RemovalReload", UUID.randomUUID(), overworld, 80032.5, 64, .5))
                .thenCompose(probe -> travel(probe, nether, true).thenCompose(v -> player(probe, () -> {
                    byte[] before = data(probe);
                    removalReload = new CompletableFuture<>(); reloadOnRemoval = probe.getUUID();
                    trigger(probe); return before;
                })).thenCompose(before -> removalReload.thenCompose(done -> arrived(probe, overworld, false, before, 0))
                        .thenCompose(v -> player(probe, () -> {
                            check(probe.level().getWorld().equals(overworld), "claimed native return completes after callback reload");
                            check(Arrays.equals(before, data(probe)), "callback reload prevents stale claimed-trip data write");
                            return null;
                        }))))
                .thenCompose(v -> global(() -> { writeSettings(true, List.of()); return null; }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void reloadDuringRemoval(EntityRemoveFromWorldEvent event) {
        if (!event.getEntity().getUniqueId().equals(reloadOnRemoval)) return;
        reloadOnRemoval = null;
        try {
            global(() -> { writeSettings(false, List.of()); return null; }).get(3, TimeUnit.SECONDS);
            check(true, "foreign native removal callback can finish global reload before returning");
            removalReload.complete(true);
        } catch (Exception ex) { removalReload.completeExceptionally(new AssertionError("Native callback held settings monitor", ex)); }
    }

    private CompletableFuture<Void> blockedReturns() {
        return blockedReturn(Axis.X, false, false).thenCompose(v -> blockedReturn(Axis.Z, false, false))
                .thenCompose(v -> blockedReturn(Axis.X, true, false)).thenCompose(v -> blockedReturn(Axis.Z, true, false))
                .thenCompose(v -> blockedReturn(Axis.X, false, true));
    }

    private CompletableFuture<Void> blockedReturn(Axis axis, boolean homeNether, boolean delayedScale) {
        boolean x = axis == Axis.X;
        World home = homeNether ? nether : overworld, shared = homeNether ? overworld : nether;
        int base = homeNether ? 20004 : delayedScale ? 152032 : 144032;
        int fallback = base - (homeNether ? 8 : 32), other = homeNether ? fallback * 8 : fallback / 8;
        String label = "axis=" + axis + " homeNether=" + homeNether + " delayedScale=" + delayedScale;
        return clearForScale(home, axis, fallback - 3, base + 5)
                .thenCompose(v -> clearForScale(shared, axis, other - 3, other + 5))
                .thenCompose(v -> frame(home, x ? base : 0, 64, x ? 0 : base, axis, 2, 3))
                .thenCompose(v -> frame(home, x ? fallback : 0, 64, x ? 0 : fallback, axis, 4, 8))
                .thenCompose(v -> frame(shared, x ? other : 0, 64, x ? 0 : other, axis, 4, 8))
                .thenCompose(v -> bot("Blocked" + axis + homeNether, UUID.randomUUID(), home,
                        x ? base + 1 : .5, 64, x ? .5 : base + 1))
                .thenCompose(probe -> travel(probe, shared, true).thenCompose(v -> travel(probe, home, true))
                        .thenCompose(v -> player(probe, () -> {
                            check(Math.abs((x ? probe.getX() : probe.getZ()) - base) < 3,
                                    "small unobstructed saved return succeeds: " + label); return null;
                        })).thenCompose(v -> travel(probe, shared, true))
                        .thenCompose(v -> player(probe, () -> {
                            probe.snapTo(x ? other + 2 : .5, 64, x ? .5 : other + 2);
                            if (delayedScale) trigger(probe);
                            probe.getBukkitEntity().getAttribute(Attribute.SCALE).setBaseValue(4); probe.refreshDimensions();
                            probe.snapTo(x ? other + 2 : .5, 64, x ? .5 : other + 2);
                            check(probe.getBoundingBox().getXsize() > 2 && probe.getBoundingBox().getYsize() > 7,
                                    "blocked-return probe has actual enlarged bounds: " + label); return null;
                        })).thenCompose(v -> delayedScale ? arrived(probe, home, false, null, 0) : travel(probe, home, false))
                        .thenCompose(v -> player(probe, () -> {
                            check(Math.abs((x ? probe.getX() : probe.getZ()) - fallback) < 4
                                    && probe.level().noBlockCollision(probe, probe.getBoundingBox()),
                                    "enlarged return uses unobstructed native frame: " + label); return null;
                        })));
    }

    private CompletableFuture<Void> clearForScale(World world, Axis axis, int start, int end) {
        boolean x = axis == Axis.X;
        return region(world, x ? start : 0, x ? 0 : start, () -> {
            for (int along = start; along <= end; along++) for (int across = -3; across <= 3; across++)
                for (int y = 64; y <= 74; y++) world.getBlockAt(x ? along : across, y, x ? across : along).setType(Material.AIR, false);
            return null;
        });
    }

    private CompletableFuture<Void> cancelledReturnContact() {
        return frame(overworld, 4832, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(overworld, 4800, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> frame(nether, 600, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot("CancelReturn", UUID.randomUUID(), overworld, 4832.5, 64, .5))
                .thenCompose(probe -> travel(probe, nether, true).thenCompose(v -> player(probe, () -> {
                    byte[] before = data(probe); cancelReturn = probe.getUUID(); trigger(probe); return before;
                })).thenCompose(before -> delay(probe, 8).thenCompose(v -> player(probe, () -> {
                    check(probe.level().getWorld().equals(nether), "foreign late cancellation prevents saved return world change");
                    check(Arrays.equals(before, data(probe)), "foreign late cancellation preserves saved pair");
                    probe.getBukkitEntity().setGameMode(org.bukkit.GameMode.CREATIVE);
                    trigger(probe); return before;
                })).thenCompose(unchanged -> delay(probe, 5).thenCompose(v -> player(probe, () -> {
                    check(probe.level().getWorld().equals(nether) && Arrays.equals(unchanged, data(probe)),
                            "zero-delay creative saved return honors foreign cancellation");
                    cancelReturn = null; return null;
                })))).thenCompose(v -> travel(probe, overworld, true)).thenCompose(v -> player(probe, () -> {
                    check(probe.level().getWorld().equals(overworld), "zero-delay creative saved return succeeds after cancellation is released");
                    return null;
                })));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void foreignCancelReturn(EntityPortalEnterEvent event) {
        if (event.getEntity().getUniqueId().equals(cancelReturn)) event.setCancelled(true);
    }

    private CompletableFuture<Void> adjacentArrival(Axis axis) {
        boolean x = axis == Axis.X;
        int base = x ? 120032 : 128032, target = base / 8 - 4;
        return frame(overworld, x ? base : 8, 64, x ? 8 : base, axis, 2, 3)
                .thenCompose(v -> frame(nether, x ? target : 0, 64, x ? 0 : target, axis, 2, 3))
                .thenCompose(v -> frame(nether, x ? target : 1, 64, x ? 1 : target, axis, 2, 3))
                .thenCompose(v -> region(nether, x ? target : 0, x ? 0 : target, () -> {
                    check(nether.getBlockAt(x ? target : 0, 64, x ? 0 : target).getType() == Material.NETHER_PORTAL
                            && nether.getBlockAt(x ? target : 1, 64, x ? 1 : target).getType() == Material.NETHER_PORTAL,
                            "both Folia arrival planes coexist before native transition on axis " + axis);
                    return null;
                }))
                .thenCompose(v -> bot("Arrival" + axis.name(), UUID.randomUUID(), overworld, x ? base + .5 : 8.1,
                        64, x ? 8.1 : base + .5))
                .thenCompose(probe -> travel(probe, nether, true)
                        .thenCompose(v -> player(probe, () -> {
                            check((x ? probe.getZ() : probe.getX()) >= 1 && (x ? probe.getZ() : probe.getX()) < 2,
                                    "native Folia arrival chooses plane 1 on axis " + axis);
                            // Persisted LinkCodec contract: first endpoint31 bytes,
                            // second endpoint starts32; its x/z are offsets48/56.
                            java.nio.ByteBuffer stored = java.nio.ByteBuffer.wrap(data(probe));
                            check(stored.getInt(x ? 56 : 48) == 1,
                                    "Folia arrival identity excludes overlapping neighboring plane on axis " + axis);
                            return null;
                        })).thenCompose(v -> relocate(probe, new Location(nether, x ? target + .5 : 1.5,
                                64, x ? 1.5 : target + .5)))
                        .thenCompose(v -> travel(probe, overworld, true))
                        .thenCompose(v -> player(probe, () -> {
                            check(Math.abs((x ? probe.getX() : probe.getZ()) - base) < 3,
                                    "adjacent Folia arrival returns to original source on axis " + axis); return null;
                        })));
    }

    private CompletableFuture<Void> scaledContact(Axis axis) {
        boolean x = axis == Axis.X;
        int base = x ? 40000 : 48000, target = base / 8;
        return frame(overworld, x ? base : 0, 64, x ? 0 : base, axis, 4, 8)
                .thenCompose(v -> frame(nether, x ? target : 0, 64, x ? 0 : target, axis, 4, 8))
                .thenCompose(v -> bot("Scaled" + axis.name(), UUID.randomUUID(), overworld,
                        x ? base + 2 : .5, 64, x ? .5 : base + 2))
                .thenCompose(probe -> travel(probe, nether, true).thenCompose(v -> relocate(probe,
                        new Location(nether, x ? target + 2 : 1.4, 64, x ? 1.4 : target + 2)))
                        .thenCompose(v -> player(probe, () -> {
                            probe.getBukkitEntity().getAttribute(Attribute.SCALE).setBaseValue(4);
                            probe.refreshDimensions();
                            check(probe.getBukkitEntity().getWidth() > 2 && probe.getBukkitEntity().getHeight() > 7,
                                    "scaled probe uses real enlarged dimensions on axis " + axis);
                            trigger(probe, true, new BlockPos(x ? target + 2 : 0, 64, x ? 0 : target + 2));
                            return null;
                        })).thenCompose(v -> arrived(probe, overworld, false, null, 0))
                        .thenCompose(v -> player(probe, () -> {
                            check(probe.level().getWorld().equals(overworld), "enlarged player touching source at body edge returns on axis " + axis);
                            probe.getBukkitEntity().getAttribute(Attribute.SCALE).setBaseValue(1); probe.refreshDimensions(); return null;
                        })));
    }

    private CompletableFuture<Void> immediateLogout(boolean returning) {
        return frame(overworld, 32032, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(nether, 4000, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot("QuitOnArrival", UUID.randomUUID(), overworld, 32032.5, 64, .5))
                .thenCompose(probe -> {
                    CompletableFuture<Void> saved = new CompletableFuture<>();
                    CompletableFuture<Void> seed = returning ? travel(probe, nether, true) : CompletableFuture.completedFuture(null);
                    return seed.thenCompose(v -> player(probe, () -> {
                        byte[] previous = data(probe);
                        afterArrival.put(probe.getUUID(), () -> {
                            try {
                                // Native logout fires PlayerQuitEvent and saves the
                                // actual player file before the next entity task.
                                probe.level().getServer().getPlayerList().remove(probe);
                                byte[] expected = data(probe);
                                check(expected != null && !Arrays.equals(previous, expected),
                                        "confirmed " + (returning ? "saved return" : "native arrival") + " is saved before immediate native logout");
                                probe.getBukkitEntity().getPersistentDataContainer().remove(key);
                                probe.getBukkitEntity().loadData();
                                check(Arrays.equals(expected, data(probe)), "immediate logout persists the confirmed pair in actual player data");
                                saved.complete(null);
                            } catch (Throwable error) { saved.completeExceptionally(error); }
                        });
                        trigger(probe); return null;
                    })).thenCompose(v -> saved);
                });
    }

    private CompletableFuture<Void> portalEffects() {
        return frame(overworld, 64032, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(nether, 8000, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot("PortalEffects", UUID.randomUUID(), overworld, 64032.5, 64, .5))
                .thenCompose(probe -> travel(probe, nether, true)
                        .thenCompose(v -> travel(probe, overworld, true))
                        .thenCompose(v -> player(probe, () -> {
                            check(portalSounds.getOrDefault(probe.getUUID(), 0) == 2,
                                    "native and saved-return journeys emit the native portal sound packet");
                            check(portalTickets.getOrDefault(probe.getUUID(), 0) >= 2,
                                    "saved return invokes the native portal ticket effect");
                            return null;
                        })).thenCompose(v -> travel(probe, nether, true))
                        .thenCompose(v -> player(probe, () -> {
                            try {
                                var field = ServerPlayer.class.getDeclaredField("enteredNetherPosition"); field.setAccessible(true);
                                check(field.get(probe) != null, "remembered Overworld-to-Nether return retains native travel origin");
                            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
                            check(portalSounds.getOrDefault(probe.getUUID(), 0) == 3,
                                    "reverse saved return also emits its native sound packet");
                            check(portalTickets.getOrDefault(probe.getUUID(), 0) >= 3,
                                    "reverse saved return also places its native portal ticket");
                            return null;
                        })).thenCompose(v -> frame(nether, 1000, 64, 0, Axis.X, 2, 3))
                        .thenCompose(v -> frame(overworld, 8000, 64, 0, Axis.X, 2, 3))
                        .thenCompose(v -> relocate(probe, new Location(nether, 1000.5, 64, .5)))
                        .thenCompose(v -> travel(probe, overworld, true))
                        .thenCompose(v -> player(probe, () -> {
                            var advancement = probe.level().getServer().getAdvancements().get(Identifier.parse("minecraft:nether/fast_travel"));
                            check(advancement != null && probe.getAdvancements().getOrStartProgress(advancement).isDone(),
                                    "long native exit after remembered entry awards the Nether travel advancement");
                            return null;
                        })));
    }

    private CompletableFuture<Void> movementRegression() {
        return frame(overworld, 27232, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(overworld, 27200, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> frame(nether, 3400, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot("WalkOnArrival", UUID.randomUUID(), overworld, 27232.5, 64, .5))
                .thenCompose(probe -> movedTrip(probe, nether, false)
                        .thenCompose(v -> movedTrip(probe, overworld, true)));
    }

    private CompletableFuture<Void> movedTrip(ServerPlayer probe, World destination, boolean returning) {
        return player(probe, () -> {
            byte[] before = data(probe);
            CompletableFuture<Location> arrival = new CompletableFuture<>();
            moveOnArrival.put(probe.getUUID(), arrival);
            trigger(probe);
            return before;
        }).thenCompose(before -> moveOnArrival.get(probe.getUUID()).thenCompose(arrival -> delay(probe, 12)
                .thenCompose(v -> player(probe, () -> {
                    check(probe.level().getWorld().equals(destination), "moving probe completes actual Folia world change");
                    check(data(probe) != null && !Arrays.equals(before, data(probe)), returning
                            ? "remembered return saves pair despite immediate movement before completion"
                            : "native arrival saves pair despite movement before next tick");
                    if (returning) check(arrival.getX() >= 27232 && arrival.getX() < 27235, "movement-safe return reaches original portal rather than vanilla neighbor");
                    moveOnArrival.remove(probe.getUUID());
                    return arrival;
                })).thenCompose(point -> relocate(probe, point))));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void moveImmediately(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Runnable action = afterArrival.remove(player.getUniqueId());
        if (action != null) {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(handle.level(), player.getLocation().getBlockX() >> 4,
                    player.getLocation().getBlockZ() >> 4, action);
        }
        CompletableFuture<Location> promise = moveOnArrival.get(player.getUniqueId());
        if (promise == null || promise.isDone()) return;
        Location arrival = player.getLocation();
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        // Finish native entity registration, then move before the next entity tick.
        RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(handle.level(), arrival.getBlockX() >> 4,
                arrival.getBlockZ() >> 4, () -> complete(promise, () -> {
                    handle.snapTo(arrival.getX() + 5, arrival.getY(), arrival.getZ() + 5);
                    check(player.getLocation().getBlock().getType() != Material.NETHER_PORTAL,
                            "probe moves before next entity tick without waiting for saved PDC");
                    return arrival;
                }));
    }

    private CompletableFuture<Void> reloadRace() {
        return frame(overworld, 5632, 64, 0, Axis.X, 2, 3)
                .thenCompose(v -> frame(overworld, 5600, 64, 0, Axis.X, 2, 3))
                .thenCompose(v -> frame(nether, 700, 64, 0, Axis.Z, 2, 3))
                .thenCompose(v -> bot("ReloadRace", UUID.randomUUID(), overworld, 5632.5, 64, .5))
                .thenCompose(probe -> travel(probe, nether, true).thenCompose(v -> player(probe, () -> {
                    byte[] before = data(probe);
                    CraftPlayer original = probe.getBukkitEntity();
                    ReloadPlayer replacement = new ReloadPlayer(probe);
                    try {
                        // Preserve the actual player's scheduler/PDC identity; only
                        // intercept the read to force the reload interleaving.
                        for (String name : new String[] {"taskScheduler", "apiScheduler", "persistentDataContainer"}) {
                            var state = CraftEntity.class.getDeclaredField(name); state.setAccessible(true);
                            state.set(replacement, state.get(original));
                        }
                        var field = Entity.class.getDeclaredField("bukkitEntity"); field.setAccessible(true); field.set(probe, replacement);
                    } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
                    replacement.beforeLinkRead = () -> writeSettings(false, List.of());
                    trigger(probe);
                    return before;
                })).thenCompose(before -> arrived(probe, overworld, false, before, 0).thenCompose(v -> player(probe, () -> {
                    check(probe.getX() < 5620, "reload between eligibility and link read keeps native route instead of disabled saved return");
                    check(Arrays.equals(before, data(probe)), "racing disabling reload preserves existing pair");
                    return null;
                }))).thenCompose(v -> global(() -> { writeSettings(true, List.of()); return null; })));
    }

    public final class ReloadPlayer extends CraftPlayer {
        Runnable beforeLinkRead;
        ReloadPlayer(ServerPlayer handle) { super((CraftServer) Bukkit.getServer(), handle); }
        @Override public CraftPersistentDataContainer getPersistentDataContainer() {
            Runnable action = beforeLinkRead;
            beforeLinkRead = null;
            if (action != null) action.run();
            return super.getPersistentDataContainer();
        }
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
        if (Bukkit.isGlobalTickThread()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "portallinked reload");
        else {
            JavaPlugin linked = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PortalLinked");
            if (!linked.onCommand(Bukkit.getConsoleSender(), linked.getCommand("portallinked"),
                    "portallinked", new String[] {"reload"})) throw new AssertionError("Reload command failed");
        }
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
        return player(nativePlayer, () -> {
                if (!nativePlayer.level().getWorld().equals(target) && nativePlayer.getBukkitEntity().getPortalCooldown() == 0)
                    trigger(nativePlayer);
                return nativePlayer.level().getWorld().equals(target)
                        && (!requireLink || (data(nativePlayer) != null && !Arrays.equals(before, data(nativePlayer))));
            })
                .thenCompose(done -> {
                    if (done) return CompletableFuture.completedFuture(null);
                    if (attempts > 400) return CompletableFuture.failedFuture(new AssertionError("Folia async trip did not arrive at " + target.getName()));
                    return arrived(nativePlayer, target, requireLink, before, attempts + 1);
                });
    }

    private void trigger(ServerPlayer player) { trigger(player, true); }

    private void trigger(ServerPlayer player, boolean ready) { trigger(player, ready, player.blockPosition()); }

    private void trigger(ServerPlayer player, boolean ready, BlockPos contact) {
        contact(player, ready, contact);
        PortalProcessor process = process(player);
        boolean accepted = process != null && process.isInsidePortalThisTick();
        handlePortal(player);
        if (accepted) sustainContact(player, process, 0);
    }

    private void sustainContact(ServerPlayer player, PortalProcessor process, int attempts) {
        BlockPos contact = process.getEntryPosition();
        if (attempts >= 200 || process(player) != process || player.getBukkitEntity().getPortalCooldown() > 0
                || !player.level().getBlockState(contact).is(Blocks.NETHER_PORTAL)
                || !player.getBukkitEntity().getBoundingBox().overlaps(new org.bukkit.util.BoundingBox(
                        contact.getX(), contact.getY(), contact.getZ(), contact.getX() + 1, contact.getY() + 1, contact.getZ() + 1))) return;
        inside(player, contact);
        if (process.isInsidePortalThisTick()) player.getBukkitEntity().getScheduler().runDelayed(this,
                task -> sustainContact(player, process, attempts + 1), null, 1);
    }

    private void handlePortal(ServerPlayer player) {
        try {
            var handle = Entity.class.getDeclaredMethod("handlePortal"); handle.setAccessible(true); handle.invoke(player);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private void contact(ServerPlayer player, boolean ready, BlockPos contact) {
        try {
            player.getBukkitEntity().setPortalCooldown(0);
            NetherPortalBlock block = (NetherPortalBlock) Blocks.NETHER_PORTAL;
            PortalProcessor process = new PortalProcessor(block, contact);
            var time = PortalProcessor.class.getDeclaredField("portalTime"); time.setAccessible(true);
            time.setInt(process, ready ? block.getPortalTransitionTime(player.level(), player) : 0);
            process.setAsInsidePortalThisTick(false);
            var field = Entity.class.getDeclaredField("portalProcess"); field.setAccessible(true); field.set(player, process);
            inside(player, contact);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private PortalProcessor process(ServerPlayer player) {
        try {
            var field = Entity.class.getDeclaredField("portalProcess"); field.setAccessible(true);
            return (PortalProcessor) field.get(player);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private void inside(ServerPlayer player, BlockPos contact) {
        try {
            NetherPortalBlock block = (NetherPortalBlock) Blocks.NETHER_PORTAL;
            var inside = NetherPortalBlock.class.getDeclaredMethod("entityInside", BlockState.class, Level.class,
                    BlockPos.class, Entity.class, InsideBlockEffectApplier.class, boolean.class);
            inside.setAccessible(true);
            inside.invoke(block, player.level().getBlockState(contact), player.level(), contact,
                    player, InsideBlockEffectApplier.NOOP, true);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private CompletableFuture<ServerPlayer> bot(String name, UUID id, World world, double x, double y, double z) {
        return region(world, (int) x, (int) z, () -> {
            try {
                ServerLevel level = (ServerLevel) world.getClass().getMethod("getHandle").invoke(world);
                MinecraftServer server = level.getServer();
                GameProfile profile = new GameProfile(id, name);
                ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault()) {
                    @Override public void placePortalTicket(BlockPos position) {
                        portalTickets.merge(getUUID(), 1, Integer::sum);
                        super.placePortalTicket(position);
                    }
                };
                Connection connection = new Connection(PacketFlow.SERVERBOUND);
                connection.channel = new EmbeddedChannel(); connection.address = new InetSocketAddress("127.0.0.1", 54321);
                player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
                    @Override public void send(Packet<?> packet) {
                        if (packet instanceof ClientboundLevelEventPacket effect && effect.getType() == 1032)
                            portalSounds.merge(player.getUUID(), 1, Integer::sum);
                    }
                    @Override public void send(Packet<?> packet, ChannelFutureListener listener) { send(packet); }
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
