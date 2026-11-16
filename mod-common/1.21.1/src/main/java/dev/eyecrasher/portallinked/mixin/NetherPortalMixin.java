// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import dev.eyecrasher.portallinked.NativePortal;
import dev.eyecrasher.portallinked.PortalLinkedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NetherPortalBlock.class)
public abstract class NetherPortalMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"))
    private void portallinked$start(ServerLevel current, Entity entity, BlockPos entered,
            CallbackInfoReturnable<DimensionTransition> callback) {
        if (entity instanceof ServerPlayer player) PortalLinkedServer.portalStarted(player, current, entered);
    }

    @Inject(method = "getPortalDestination", at = @At("RETURN"))
    private void portallinked$resolved(ServerLevel current, Entity entity, BlockPos entered,
            CallbackInfoReturnable<DimensionTransition> callback) {
        if (entity instanceof ServerPlayer player) PortalLinkedServer.portalResolved(player, callback.getReturnValue());
    }

    @Inject(method = "getExitPortal", at = @At("HEAD"), cancellable = true)
    private void portallinked$return(ServerLevel target, Entity entity, BlockPos entered, BlockPos approximate,
            boolean nether, WorldBorder border, CallbackInfoReturnable<DimensionTransition> callback) {
        if (!(entity instanceof ServerPlayer player)) return;
        NativePortal destination = PortalLinkedServer.rememberedReturn(player, target);
        if (destination == null) return;
        DimensionTransition transition = NetherPortalAccess.portallinked$transition(entity, entered, destination.rectangle(), target,
                DimensionTransition.PLAY_PORTAL_SOUND.then(DimensionTransition.PLACE_PORTAL_TICKET));
        if (target.noCollision(player, player.getBoundingBox().move(transition.pos().subtract(player.position()))))
            callback.setReturnValue(transition);
    }
}
