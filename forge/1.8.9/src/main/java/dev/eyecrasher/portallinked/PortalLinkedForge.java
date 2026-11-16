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
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;

@Mod(modid = "portallinked", name = "PortalLinked", version = "1.2.0+1.8.9",
        acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*", serverSideOnly = true,
        dependencies = "required-after:Forge@[11.15.1.2318,)")
public final class PortalLinkedForge {
    @Mod.EventHandler public void initialize(FMLPreInitializationEvent event) {
        PortalHooks.initialize(event.getModConfigurationDirectory().toPath().resolve("portallinked.json"));
        MinecraftForge.EVENT_BUS.register(this);
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().bus() != MinecraftForge.EVENT_BUS)
            net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(this);
    }
    @Mod.EventHandler public void starting(FMLServerStartingEvent event) {
        event.registerServerCommand(new AdminCommand());
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
                reply(sender, "Forgot portal link for " + target.getName() + ".");
            } else throw new CommandException(getCommandUsage(sender));
        }
        @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos position) {
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, Arrays.asList("reload", "status", "forget"));
            if (args.length == 2 && args[0].equals("forget")) return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
            return Collections.emptyList();
        }
        private static void reply(ICommandSender sender, String message) { sender.addChatMessage(new ChatComponentText(message)); }
    }
}
