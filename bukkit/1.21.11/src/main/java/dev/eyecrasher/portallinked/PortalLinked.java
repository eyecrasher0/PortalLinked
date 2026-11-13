// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Only the last successful crossing is remembered, separately for each player. */
record PortalLinked(PortalRef departure, PortalRef arrival) {
    PortalLinked {
        Objects.requireNonNull(departure);
        Objects.requireNonNull(arrival);
        if (departure.world().equals(arrival.world())) throw new IllegalArgumentException("Same world");
    }

    Optional<PortalRef> returnPortal(PortalRef entered, UUID targetWorld) {
        return arrival.equals(entered) && departure.world().equals(targetWorld)
                ? Optional.of(departure) : Optional.empty();
    }
}
