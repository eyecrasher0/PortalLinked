// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import dev.eyecrasher.portallinked.PortalLinkedServer;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Register again when vanilla rebuilds its dispatcher during datapack reload. */
@Mixin(value = Commands.class, remap = false)
public abstract class CommandsMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void portallinked$commands(CallbackInfo callback) {
        PortalLinkedServer.registerCommands(((Commands) (Object) this).getDispatcher());
    }
}
