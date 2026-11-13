// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.mixin;

import dev.eyecrasher.fixture.NativeIntegration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, priority = 500, remap = false)
public abstract class CancelTeleportFixtureMixin {
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"), cancellable = true)
    private void portallinked$cancel(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> callback) {
        if (((ServerPlayer) (Object) this).getUUID().equals(NativeIntegration.cancelTeleport)) callback.setReturnValue(null);
    }
}
