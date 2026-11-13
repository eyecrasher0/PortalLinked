// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.fixture.mixin;

import io.netty.channel.Channel;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Typed accessors are remapped with the disposable fixture for production. */
@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("channel") void portallinked$channel(Channel channel);
    @Accessor("address") void portallinked$address(SocketAddress address);
}
