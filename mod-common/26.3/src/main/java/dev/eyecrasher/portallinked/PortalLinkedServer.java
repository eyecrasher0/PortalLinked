// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.slf4j.Logger;

/** Server-only state shared by the three native loader adapters. */
public final class PortalLinkedServer {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private static final Path CONFIG = Path.of("config", "portallinked.json");
    private static Settings settings = new Settings(false, Set.of(), false);
    private static long generation;

    private PortalLinkedServer() {}

    public static void initialize(MinecraftServer server) {
        settings = new Settings(false, Set.of(), false);
        generation++;
        try {
            if (!Files.exists(CONFIG)) {
                Files.createDirectories(CONFIG.getParent());
                Files.writeString(CONFIG, "{\n  \"enabled\": true,\n  \"excluded-worlds\": [],\n  \"debug\": false\n}\n", StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            LOG.warn("PortalLinked could not create its configuration; linking remains disabled.");
        }
        reload();
        LOG.info("PortalLinked enabled.");
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("portallinked")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("reload").executes(context -> {
                    boolean success = reload();
                    context.getSource().sendSuccess(() -> Component.literal(success ? "PortalLinked configuration reloaded."
                            : "PortalLinked configuration could not be reloaded. Previous settings are unchanged; check the server log."), false);
                    return success ? 1 : 0;
                }))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("PortalLinked: enabled=" + settings.enabled
                            + ", excluded-worlds=" + settings.excluded + ", debug=" + settings.debug + "."), false);
                    return 1;
                }))
                .then(Commands.literal("forget").executes(context -> {
                    ServerPlayer target = context.getSource().getPlayerOrException();
                    forget(target);
                    context.getSource().sendSuccess(() -> Component.literal("Forgot portal link for "
                            + target.getScoreboardName() + "."), false);
                    return 1;
                }).then(Commands.argument("player", EntityArgument.player()).executes(context -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                    forget(target);
                    context.getSource().sendSuccess(() -> Component.literal("Forgot portal link for " + target.getScoreboardName() + "."), false);
                    return 1;
                }))));
    }

    private static boolean reload() {
        try (var reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            JsonObject object = JSON.fromJson(reader, JsonObject.class);
            if (object == null || !object.has("enabled") || !object.get("enabled").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("enabled").isBoolean() || !object.has("debug")
                    || !object.get("debug").isJsonPrimitive() || !object.getAsJsonPrimitive("debug").isBoolean()
                    || !object.has("excluded-worlds") || !object.get("excluded-worlds").isJsonArray())
                throw new IllegalArgumentException("Required configuration fields");
            Set<String> excluded = new HashSet<>();
            for (var element : object.getAsJsonArray("excluded-worlds")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Dimension names must be strings");
                String name = element.getAsString();
                if (name.indexOf(':') <= 0 || Identifier.tryParse(name) == null)
                    throw new IllegalArgumentException("Use complete dimension identifiers");
                excluded.add(name);
            }
            Settings next = new Settings(object.get("enabled").getAsBoolean(), Set.copyOf(excluded), object.get("debug").getAsBoolean());
            settings = next;
            generation++;
            return true;
        } catch (IOException | RuntimeException ex) {
            LOG.warn("PortalLinked cannot load config/portallinked.json ({}); previous settings are unchanged. Check JSON and setting types.", ex.getClass().getSimpleName());
            return false;
        }
    }

    public static void portalStarted(ServerPlayer player, ServerLevel level, BlockPos entered) {
        PlayerLinkData data = (PlayerLinkData) player;
        data.portallinked$setPending(null);
        ServerLevel target = level.getServer().getLevel(level.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
        if (target == null || !activePair(level, target)) return;
        NativePortal.at(level, entered).ifPresent(source -> data.portallinked$setPending(
                new PlayerLinkData.PendingTrip(source, level, null, generation)));
    }

    public static void portalResolved(ServerPlayer player, TeleportTransition transition) {
        PlayerLinkData data = (PlayerLinkData) player;
        var pending = data.portallinked$getPending();
        if (pending == null) return;
        data.portallinked$setPending(transition != null && pending.generation() == generation
                && activePair(pending.sourceLevel(), transition.newLevel())
                ? new PlayerLinkData.PendingTrip(pending.departure(), pending.sourceLevel(), transition, generation) : null);
    }

    public static NativePortal rememberedReturn(ServerPlayer player, ServerLevel target) {
        PlayerLinkData data = (PlayerLinkData) player;
        var pending = data.portallinked$getPending();
        PortalPair pair = data.portallinked$getPair();
        if (pending == null || pair == null || pending.generation() != generation
                || !activePair(pending.sourceLevel(), target)) return null;
        NativePortal destination = pair.returnPortal(pending.departure(), target.dimension().identifier().toString()).orElse(null);
        if (destination != null && destination.usable(target)) {
            debug("Returning " + player.getScoreboardName() + " to their saved portal.");
            return destination;
        }
        if (destination != null) debug("Saved return unavailable for " + player.getScoreboardName() + "; using vanilla lookup.");
        return null;
    }

    public static void beforeTeleport(ServerPlayer player, TeleportTransition transition) {
        PlayerLinkData data = (PlayerLinkData) player;
        var pending = data.portallinked$getPending();
        data.portallinked$setPending(null);
        data.portallinked$setActive(pending != null && pending.transition() == transition ? pending : null);
    }

    public static void afterTeleport(ServerPlayer player, ServerPlayer result) {
        PlayerLinkData data = (PlayerLinkData) player;
        var trip = data.portallinked$getActive();
        data.portallinked$setActive(null);
        if (trip == null || result != player || trip.generation() != generation
                || trip.transition().newLevel() != player.level() || !activePair(trip.sourceLevel(), player.level())
                || !trip.departure().usable(trip.sourceLevel())) return;
        NativePortal.touching(player).filter(destination -> destination.usable(player.level())).ifPresent(destination -> {
            data.portallinked$setPair(new PortalPair(trip.departure(), destination));
            debug("Saved portal link for " + player.getScoreboardName() + ".");
        });
    }

    private static boolean activePair(ServerLevel from, ServerLevel to) {
        return settings.enabled && !settings.excluded.contains(from.dimension().identifier().toString())
                && !settings.excluded.contains(to.dimension().identifier().toString())
                && ((from.dimension() == Level.OVERWORLD && to.dimension() == Level.NETHER)
                || (from.dimension() == Level.NETHER && to.dimension() == Level.OVERWORLD));
    }

    private static void forget(ServerPlayer player) {
        PlayerLinkData data = (PlayerLinkData) player;
        data.portallinked$setPair(null); data.portallinked$setPending(null); data.portallinked$setActive(null);
    }

    private static void debug(String message) { if (settings.debug) LOG.info(message); }
    private record Settings(boolean enabled, Set<String> excluded, boolean debug) {}
}
