package ai.handleon.driveprint

import kotlin.test.*

class LostWayTest {
    private val trip = featureTrip().copy(guided = true, familiarDest = true, reroutes = 2)
    @Test fun `two reroutes to a familiar place are flagged`() = assertTrue(LostWay.flagged(trip))
    @Test fun `one reroute is not`() = assertFalse(LostWay.flagged(trip.copy(reroutes = 1)))
    @Test fun `unfamiliar destination is not`() = assertFalse(LostWay.flagged(trip.copy(familiarDest = false)))
    @Test fun `unguided trip is not`() = assertFalse(LostWay.flagged(trip.copy(guided = false)))
}
