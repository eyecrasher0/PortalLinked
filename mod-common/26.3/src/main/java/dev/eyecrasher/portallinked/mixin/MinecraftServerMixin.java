// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import dev.eyecrasher.portallinked.PortalLinkedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class MinecraftServerMixin {
    @Inject(method = "loadLevel", at = @At("TAIL"))
    private void portallinked$start(CallbackInfo callback) {
        PortalLinkedServer.initialize((MinecraftServer) (Object) this);
    }
}
