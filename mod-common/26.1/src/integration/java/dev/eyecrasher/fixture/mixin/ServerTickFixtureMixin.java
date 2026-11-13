// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.mixin;

import dev.eyecrasher.fixture.NativeIntegration;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, priority = 500, remap = false)
public abstract class ServerTickFixtureMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void portallinked$test(CallbackInfo callback) {
        if (Boolean.getBoolean("portallinked.integration")) NativeIntegration.run((MinecraftServer) (Object) this);
    }
}
