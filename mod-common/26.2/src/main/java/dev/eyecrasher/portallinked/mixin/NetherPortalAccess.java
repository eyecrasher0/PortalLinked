// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = NetherPortalBlock.class, remap = false)
public interface NetherPortalAccess {
    @Invoker("getDimensionTransitionFromExit")
    static TeleportTransition portallinked$transition(Entity entity, BlockPos entry, BlockUtil.FoundRectangle exit,
            ServerLevel target, TeleportTransition.PostTeleportTransition after) { throw new AssertionError(); }
}
