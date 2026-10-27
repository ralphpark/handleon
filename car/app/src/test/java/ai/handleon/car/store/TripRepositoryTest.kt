package ai.handleon.car.store

import ai.handleon.car.sync.patternTrip
import org.junit.Test
import kotlin.test.assertEquals

class TripRepositoryTest {
    @Test fun `trips excludes pending and other but keeps rows without status`() {
        val trips = (1L..4).map { patternTrip().copy(startMs = it) }
        val statuses = mapOf(1L to DriverStatus.OWNER, 2L to DriverStatus.PENDING, 3L to DriverStatus.OTHER)
        assertEquals(listOf(trips[0], trips[3]), ownerTrips(trips, statuses))
    }
}
