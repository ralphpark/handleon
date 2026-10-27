package ai.handleon.driveprint

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AdaptTest {
    @Test fun `weekly counts interventions per 100 km`() {
        val monday = LocalDate.of(2026, 4, 6)
        val trips = listOf(
            featureTrip(monday.plusDays(6)).copy(distanceKm = 30.0, lkaEvents = 2, fcaEvents = 1),
            featureTrip(monday).copy(distanceKm = 20.0, lkaEvents = 1, fcaEvents = 1),
        )
        val week = Adapt.weekly(trips, KST).single()
        assertEquals(AdaptWeek(monday, 50.0, 3, 2, 10.0), week)
        assertEquals(week, Json.decodeFromString<AdaptWeek>(Json.encodeToString(week)))
    }

    @Test fun `short weeks have no rate`() {
        val monday = LocalDate.of(2026, 4, 6)
        val weeks = Adapt.weekly(listOf(
            featureTrip(monday).copy(distanceKm = 19.99, lkaEvents = 1),
            featureTrip(monday.plusWeeks(1)).copy(distanceKm = 20.0, lkaEvents = 1),
            featureTrip(monday.plusWeeks(2)).copy(distanceKm = 0.0),
        ), KST)
        assertNull(weeks[0].per100km)
        assertEquals(1, weeks[0].lka)
        assertEquals(5.0, weeks[1].per100km)
        assertNull(weeks[2].per100km)
    }

    @Test fun `week boundary follows supplied zone and trip start`() {
        val monday = LocalDate.of(2026, 4, 13)
        val trips = listOf(featureTrip(monday, hour = 0), featureTrip(monday.minusDays(1), minutes = 120, hour = 23))
        assertEquals(listOf(monday.minusWeeks(1), monday), Adapt.weekly(trips, KST).map { it.weekStart })
        assertEquals(listOf(monday.minusWeeks(1)), Adapt.weekly(trips, ZoneId.of("UTC")).map { it.weekStart })
    }

    @Test fun `absent weeks are not invented as zero driving`() {
        val monday = LocalDate.of(2026, 4, 6)
        val weeks = Adapt.weekly(listOf(featureTrip(monday.plusWeeks(2)), featureTrip(monday)), KST)
        assertEquals(listOf(monday, monday.plusWeeks(2)), weeks.map { it.weekStart })
        assertTrue(Adapt.weekly(emptyList(), KST).isEmpty())
    }
}
