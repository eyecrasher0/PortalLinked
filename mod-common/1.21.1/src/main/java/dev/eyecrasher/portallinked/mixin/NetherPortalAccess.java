// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = NetherPortalBlock.class)
public interface NetherPortalAccess {
    @Invoker("getDimensionTransitionFromExit")
    static DimensionTransition portallinked$transition(Entity entity, BlockPos entry, BlockUtil.FoundRectangle exit,
            ServerLevel target, DimensionTransition.PostDimensionTransition after) { throw new AssertionError(); }
}
