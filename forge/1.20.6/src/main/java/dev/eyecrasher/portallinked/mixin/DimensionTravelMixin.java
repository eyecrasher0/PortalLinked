// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import dev.eyecrasher.portallinked.PortalLinkedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class DimensionTravelMixin {
    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"))
    private void portallinked$depart(ServerLevel target, ITeleporter teleporter, CallbackInfoReturnable<Entity> callback) {
        PortalLinkedServer.beforeTeleport((ServerPlayer) (Object) this, target);
    }

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"))
    private void portallinked$arrive(ServerLevel target, ITeleporter teleporter, CallbackInfoReturnable<Entity> callback) {
        PortalLinkedServer.afterTeleport((ServerPlayer) (Object) this, callback.getReturnValue());
    }
}
