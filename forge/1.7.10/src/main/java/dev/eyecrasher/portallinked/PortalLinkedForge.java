// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;

@Mod(modid = "portallinked", name = "PortalLinked", version = "1.2.0+1.7.10",
        acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*",
        dependencies = "required-after:Forge@[10.13.4.1614,)")
public final class PortalLinkedForge {
    @Mod.EventHandler public void initialize(FMLPreInitializationEvent event) {
        if (event.getSide().isClient()) return;
        PortalHooks.initialize(event.getModConfigurationDirectory().toPath().resolve("portallinked.json"));
        MinecraftForge.EVENT_BUS.register(this);
        if (cpw.mods.fml.common.FMLCommonHandler.instance().bus() != MinecraftForge.EVENT_BUS)
            cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this);
    }
    @Mod.EventHandler public void starting(FMLServerStartingEvent event) {
        event.registerServerCommand(new AdminCommand());
    }
    @SubscribeEvent public void tick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase == cpw.mods.fml.common.gameevent.TickEvent.Phase.END) PortalHooks.expireContacts();
    }
    @SubscribeEvent public void loggedOut(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        PortalHooks.forgotten(event.player.getUniqueID());
    }
    @Mod.EventHandler public void stopping(FMLServerStoppingEvent event) { PortalHooks.stopped(); }
    @SubscribeEvent public void changed(PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) PortalHooks.changed((EntityPlayerMP) event.player, event.fromDim, event.toDim);
    }
    @SubscribeEvent public void clone(PlayerEvent.Clone event) { PortalPair.copy(event.original, event.entityPlayer); }

    private static final class AdminCommand extends CommandBase {
        @Override public String getCommandName() { return "portallinked"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/portallinked <reload|status|forget> [online-player]"; }
        @Override public int getRequiredPermissionLevel() { return 2; }
        @Override public void processCommand(ICommandSender sender, String[] args) throws CommandException {
            if (args.length == 1 && args[0].equals("status")) reply(sender, PortalHooks.status());
            else if (args.length == 1 && args[0].equals("reload"))
                reply(sender, PortalHooks.reload() ? "PortalLinked configuration reloaded."
                        : "PortalLinked configuration could not be reloaded. Previous settings are unchanged; check the server log.");
            else if ((args.length == 1 || args.length == 2) && args[0].equals("forget")) {
                EntityPlayerMP target = args.length == 2 ? getPlayer(sender, args[1]) : getCommandSenderAsPlayer(sender);
                PortalPair.save(target, null); PortalHooks.portalFinished(target);
                reply(sender, "Forgot portal link for " + target.getCommandSenderName() + ".");
            } else throw new CommandException(getCommandUsage(sender));
        }
        @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, new String[]{"reload", "status", "forget"});
            if (args.length == 2 && args[0].equals("forget")) return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
            return Collections.emptyList();
        }
        private static void reply(ICommandSender sender, String message) { sender.addChatMessage(new ChatComponentText(message)); }
    }
}
