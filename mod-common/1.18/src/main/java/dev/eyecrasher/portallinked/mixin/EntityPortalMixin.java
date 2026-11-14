// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import dev.eyecrasher.portallinked.PlayerLinkData;
import dev.eyecrasher.portallinked.PortalLinkedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.PortalInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityPortalMixin {
    @Shadow protected BlockPos portalEntrancePos;

    @Inject(method = "handleNetherPortal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;"))
    private void portallinked$accepted(CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player)
            PortalLinkedServer.portalStarted(player, player.getLevel(), portalEntrancePos);
    }

    @Inject(method = "handleNetherPortal", at = @At("RETURN"))
    private void portallinked$finished(CallbackInfo callback) {
        if ((Object) this instanceof PlayerLinkData player) {
            player.portallinked$setPending(null);
            player.portallinked$setActive(null);
        }
    }

    @Inject(method = "findDimensionEntryPoint", at = @At("HEAD"), cancellable = true)
    private void portallinked$return(ServerLevel target, CallbackInfoReturnable<PortalInfo> callback) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        PortalInfo remembered = PortalLinkedServer.rememberedReturn(player, target);
        if (remembered != null) {
            PortalLinkedServer.portalResolved(player, target, remembered);
            callback.setReturnValue(remembered);
        }
    }

    @Inject(method = "findDimensionEntryPoint", at = @At("RETURN"))
    private void portallinked$resolved(ServerLevel target, CallbackInfoReturnable<PortalInfo> callback) {
        if ((Object) this instanceof ServerPlayer player)
            PortalLinkedServer.portalResolved(player, target, callback.getReturnValue());
    }
}
