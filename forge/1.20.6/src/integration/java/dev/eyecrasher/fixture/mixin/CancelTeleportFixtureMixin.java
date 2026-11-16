// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.mixin;

import dev.eyecrasher.fixture.NativeIntegration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, priority = 500)
public abstract class CancelTeleportFixtureMixin {
    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void portallinked$cancel(ServerLevel target, ITeleporter teleporter, CallbackInfoReturnable<Entity> callback) {
        var probe = NativeIntegration.beforeDimension;
        NativeIntegration.beforeDimension = null;
        if (probe != null) {
            try { probe.run((ServerPlayer) (Object) this); }
            catch (Exception ex) { throw new AssertionError(ex); }
        }
        if (((ServerPlayer) (Object) this).getUUID().equals(NativeIntegration.cancelTeleport)) callback.setReturnValue(null);
    }
}
