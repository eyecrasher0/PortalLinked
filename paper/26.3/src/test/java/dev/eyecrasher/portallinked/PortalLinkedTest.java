// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 EyeCrasher
package dev.eyecrasher.portallinked;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Axis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PortalLinkedTest {
    private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PortalRef HOME = new PortalRef(OVERWORLD, -832, 64, -17, Axis.X, 2, 3);
    private static final PortalRef SHARED = new PortalRef(NETHER, -100, 80, -2, Axis.Z, 4, 5);

    @Test void sameArrivalReturnsToDeparture() {
        assertEquals(HOME, new PortalLinked(HOME, SHARED).returnPortal(SHARED, OVERWORLD).orElseThrow());
    }

    @Test void differentPortalKeepsVanillaTravel() {
        PortalRef other = new PortalRef(NETHER, 1000, 80, -2, Axis.Z, 4, 5);
        assertTrue(new PortalLinked(HOME, SHARED).returnPortal(other, OVERWORLD).isEmpty());
    }

    @Test void sameCoordinatesInAnotherWorldAreDifferent() {
        PortalRef anotherWorld = new PortalRef(UUID.randomUUID(), SHARED.x(), SHARED.y(), SHARED.z(),
                SHARED.axis(), SHARED.width(), SHARED.height());
        assertTrue(new PortalLinked(HOME, SHARED).returnPortal(anotherWorld, OVERWORLD).isEmpty());
    }

    @Test void resizedPortalIsDifferent() {
        PortalRef resized = new PortalRef(NETHER, SHARED.x(), SHARED.y(), SHARED.z(), SHARED.axis(), 5, 5);
        assertTrue(new PortalLinked(HOME, SHARED).returnPortal(resized, OVERWORLD).isEmpty());
    }

    @Test void targetWorldMustMatch() {
        assertTrue(new PortalLinked(HOME, SHARED).returnPortal(SHARED, UUID.randomUUID()).isEmpty());
    }

    @Test void playersCanHaveDifferentHomesAtSharedPortal() {
        PortalRef neighbor = new PortalRef(OVERWORLD, -800, 64, -17, Axis.X, 2, 3);
        PortalLinked playerOne = new PortalLinked(HOME, SHARED), playerTwo = new PortalLinked(neighbor, SHARED);
        assertEquals(HOME, playerOne.returnPortal(SHARED, OVERWORLD).orElseThrow());
        assertEquals(neighbor, playerTwo.returnPortal(SHARED, OVERWORLD).orElseThrow());
    }

    @Test void successfulReturnRemembersTheReverseJourney() {
        PortalLinked outward = new PortalLinked(HOME, SHARED);
        PortalLinked returned = new PortalLinked(outward.arrival(), outward.returnPortal(SHARED, OVERWORLD).orElseThrow());
        assertEquals(SHARED, returned.returnPortal(HOME, NETHER).orElseThrow());
    }

    @Test void newJourneyDoesNotForceAnOldReturn() {
        PortalRef newArrival = new PortalRef(OVERWORLD, 8000, 64, 0, Axis.X, 2, 3);
        PortalRef newDeparture = new PortalRef(NETHER, 1000, 64, 0, Axis.X, 2, 3);
        PortalLinked newJourney = new PortalLinked(newDeparture, newArrival);
        assertEquals(newDeparture, newJourney.returnPortal(newArrival, NETHER).orElseThrow());
        assertTrue(newJourney.returnPortal(SHARED, OVERWORLD).isEmpty());
    }

    static Stream<PortalLinked> roundTrips() {
        return Stream.of(new PortalLinked(HOME, SHARED), new PortalLinked(SHARED, HOME),
                new PortalLinked(new PortalRef(OVERWORLD, -29_999_900, -63, 0, Axis.Z, 21, 21),
                        new PortalRef(NETHER, 3_000_000, 100, -3_000_000, Axis.X, 2, 3)));
    }

    @ParameterizedTest @MethodSource("roundTrips")
    void dataSurvivesSerialization(PortalLinked link) {
        assertEquals(link, LinkCodec.decode(LinkCodec.encode(link)).orElseThrow());
    }

    static Stream<byte[]> invalidData() {
        byte[] valid = LinkCodec.encode(new PortalLinked(HOME, SHARED));
        byte[] version = valid.clone(); version[0] = 127;
        byte[] axis = valid.clone(); axis[29] = 2;
        byte[] width = valid.clone(); width[30] = 1;
        byte[] height = valid.clone(); height[31] = (byte) 255;
        byte[] sameWorld = valid.clone(); System.arraycopy(sameWorld, 1, sameWorld, 32, 16);
        return Stream.of(new byte[0], new byte[1], Arrays.copyOf(valid, valid.length - 1),
                Arrays.copyOf(valid, valid.length + 1), version, axis, width, height, sameWorld);
    }

    @ParameterizedTest @MethodSource("invalidData")
    void corruptDataFallsBackToVanilla(byte[] data) {
        assertTrue(LinkCodec.decode(data).isEmpty());
    }

    @Test void absentDataFallsBackToVanilla() { assertTrue(LinkCodec.decode(null).isEmpty()); }

    @Test void invalidGeometryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PortalRef(OVERWORLD, 0, 0, 0, Axis.Y, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new PortalRef(OVERWORLD, 0, 0, 0, Axis.X, 22, 3));
        assertThrows(IllegalArgumentException.class, () -> new PortalRef(OVERWORLD, 0, 0, 0, Axis.X, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new PortalLinked(HOME, HOME));
    }
}
