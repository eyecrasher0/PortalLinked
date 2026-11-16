// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface PortalContactAccess {
    @Accessor("portalTime") void portallinked$portalTime(int ticks);
    @Invoker("getPortalWaitTime") int portallinked$portalWait();
    @Invoker("handleNetherPortal") void portallinked$travel();
}
