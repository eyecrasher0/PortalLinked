// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Only the last successful crossing is remembered, separately for each player. */
final class PortalLinked {
    private final PortalRef departure;
    private final PortalRef arrival;

    public PortalLinked(PortalRef departure, PortalRef arrival) {
        Objects.requireNonNull(departure);
        Objects.requireNonNull(arrival);
        if (departure.world().equals(arrival.world())) throw new IllegalArgumentException("Same world");

        this.departure = departure;
        this.arrival = arrival;
    }

    public PortalRef departure() { return departure; }
    public PortalRef arrival() { return arrival; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PortalLinked)) return false;
        PortalLinked value = (PortalLinked) other;
        return java.util.Objects.equals(departure, value.departure) && java.util.Objects.equals(arrival, value.arrival);
    }
    @Override public int hashCode() { return java.util.Objects.hash(departure, arrival); }
    @Override public String toString() { return "PortalLinked[" + "departure=" + departure + ", " + "arrival=" + arrival + "]"; }



    Optional<PortalRef> returnPortal(PortalRef entered, UUID targetWorld) {
        return arrival.equals(entered) && departure.world().equals(targetWorld)
                ? Optional.of(departure) : Optional.empty();
    }
}
