// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.PortalInfo;

public interface PlayerLinkData {
    PortalPair portallinked$getPair();
    void portallinked$setPair(PortalPair pair);
    PendingTrip portallinked$getPending();
    void portallinked$setPending(PendingTrip trip);
    PendingTrip portallinked$getActive();
    void portallinked$setActive(PendingTrip trip);

    final class PendingTrip {
        private final NativePortal departure;
        private final ServerLevel sourceLevel;
        private final ServerLevel targetLevel;
        private final PortalInfo transition;
        private final long generation;

        public PendingTrip(NativePortal departure, ServerLevel sourceLevel, ServerLevel targetLevel, PortalInfo transition, long generation) {
            this.departure = departure;
            this.sourceLevel = sourceLevel;
            this.targetLevel = targetLevel;
            this.transition = transition;
            this.generation = generation;
        }

        public NativePortal departure() { return departure; }
        public ServerLevel sourceLevel() { return sourceLevel; }
        public ServerLevel targetLevel() { return targetLevel; }
        public PortalInfo transition() { return transition; }
        public long generation() { return generation; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PendingTrip)) return false;
            PendingTrip value = (PendingTrip) other;
            return java.util.Objects.equals(departure, value.departure) && java.util.Objects.equals(sourceLevel, value.sourceLevel) && java.util.Objects.equals(targetLevel, value.targetLevel) && java.util.Objects.equals(transition, value.transition) && java.util.Objects.equals(generation, value.generation);
        }
        @Override public int hashCode() { return java.util.Objects.hash(departure, sourceLevel, targetLevel, transition, generation); }
        @Override public String toString() { return "PendingTrip[" + "departure=" + departure + ", " + "sourceLevel=" + sourceLevel + ", " + "targetLevel=" + targetLevel + ", " + "transition=" + transition + ", " + "generation=" + generation + "]"; }
}
}
