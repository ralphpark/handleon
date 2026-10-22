package ai.handleon.driveprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GeoTest {
    @Test fun cityHallToGangnamIsAbout8_8km() =
        assertEquals(8.78, Geo.haversineKm(LatLon(37.5663, 126.9779), LatLon(37.4979, 127.0276)), 0.1)
    @Test fun pointsInSameKmCellShareId() =
        assertEquals(Geo.cell(LatLon(37.4005, 127.1005)), Geo.cell(LatLon(37.4010, 127.1010)))
    @Test fun pointsTwoKmApartDiffer() =
        assertNotEquals(Geo.cell(LatLon(37.400, 127.100)), Geo.cell(LatLon(37.418, 127.100)))
    @Test fun headingDeltaWrapsAround() {
        assertEquals(20.0, Geo.headingDelta(350.0, 10.0), 1e-9)
        assertEquals(-90.0, Geo.headingDelta(90.0, 0.0), 1e-9)
    }
}
