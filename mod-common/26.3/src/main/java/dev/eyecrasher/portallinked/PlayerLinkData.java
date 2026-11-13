// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.TeleportTransition;

public interface PlayerLinkData {
    PortalPair portallinked$getPair();
    void portallinked$setPair(PortalPair pair);
    PendingTrip portallinked$getPending();
    void portallinked$setPending(PendingTrip trip);
    PendingTrip portallinked$getActive();
    void portallinked$setActive(PendingTrip trip);

    record PendingTrip(NativePortal departure, ServerLevel sourceLevel, TeleportTransition transition, long generation) {}
}
