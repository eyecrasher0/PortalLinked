// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import dev.eyecrasher.portallinked.PlayerLinkData;
import dev.eyecrasher.portallinked.PortalLinkedServer;
import dev.eyecrasher.portallinked.PortalPair;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, remap = false)
public abstract class ServerPlayerMixin implements PlayerLinkData {
    @Unique private PortalPair portallinked$pair;
    @Unique private PendingTrip portallinked$pending;
    @Unique private PendingTrip portallinked$active;
    @Override public PortalPair portallinked$getPair() { return portallinked$pair; }
    @Override public void portallinked$setPair(PortalPair pair) { portallinked$pair = pair; }
    @Override public PendingTrip portallinked$getPending() { return portallinked$pending; }
    @Override public void portallinked$setPending(PendingTrip trip) { portallinked$pending = trip; }
    @Override public PendingTrip portallinked$getActive() { return portallinked$active; }
    @Override public void portallinked$setActive(PendingTrip trip) { portallinked$active = trip; }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void portallinked$load(ValueInput input, CallbackInfo callback) { portallinked$pair = PortalPair.read(input); }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void portallinked$save(ValueOutput output, CallbackInfo callback) {
        if (portallinked$pair != null) portallinked$pair.write(output);
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void portallinked$respawn(ServerPlayer old, boolean keepEverything, CallbackInfo callback) {
        portallinked$pair = ((PlayerLinkData) old).portallinked$getPair();
        portallinked$pending = null; portallinked$active = null;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void portallinked$expire(CallbackInfo callback) { portallinked$pending = null; portallinked$active = null; }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"))
    private void portallinked$depart(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> callback) {
        PortalLinkedServer.beforeTeleport((ServerPlayer) (Object) this, transition);
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("RETURN"))
    private void portallinked$arrive(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> callback) {
        PortalLinkedServer.afterTeleport((ServerPlayer) (Object) this, callback.getReturnValue());
    }
}
