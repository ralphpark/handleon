package ai.handleon.driveprint

import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class HabitsTest {
    private val from = LocalDate.of(2026, 4, 6)
    private fun h(kept: Int, total: Int, brakes: Int, minutes: Int) =
        TripHabits(0, minutes, kept, total, brakes, 0, null, false, false)
    @Test fun `line praises full signal use`() = assertEquals(
        "방향지시등 4번 모두 켜셨어요 · 급제동 없음 · 52분", Habits.line(h(4, 4, 0, 52)))
    @Test fun `line states partial signal use plainly`() = assertEquals(
        "방향지시등 4번 중 3번 켜셨어요 · 급제동 없음 · 20분", Habits.line(h(3, 4, 0, 20)))
    @Test fun `line without announced turns`() = assertEquals("급제동 2번 · 15분", Habits.line(h(0, 0, 2, 15)))
    @Test fun `lines never use score or danger words`() {
        val words = Copy.FORBIDDEN + listOf("점수", "위험", "치매", "진단", "등급", "순위", "면허 반납")
        for (total in 0..4) for (kept in 0..total) for (brakes in 0..2) for (accels in 0..2) {
            val line = Habits.line(h(kept, total, brakes, 20).copy(hardAccels = accels))
            words.forEach { assertFalse(it in line, line) }
        }
    }
    @Test fun `line mentions only one improvement and puts praise first`() {
        assertEquals("방향지시등 4번 중 3번 켜셨어요 · 20분", Habits.line(h(3, 4, 2, 20)))
        assertEquals("급제동 없음 · 20분", Habits.line(h(0, 0, 0, 20)))
    }
    @Test fun `summary counts calm trips and long stretches`() {
        val trips = listOf(featureTrip(from, 70), featureTrip(from, 60, 12), featureTrip(from.plusDays(1), 20).copy(hardBrakes = 1))
        val outing = Outing(trips.first().startMs, trips[1].endMs, trips.take(2).map { it.startMs }, 130 * 60_000L, false, 130 * 60_000L, true)
        val result = Habits.summary(trips, listOf(outing), from, from.plusDays(7), KST)
        assertEquals(2, result.calmTrips)
        assertEquals(1, result.longStretches)
        assertEquals(3, result.trips)
        assertEquals(150, result.minutes)
        assertEquals(result, Json.decodeFromString<HabitSummary>(Json.encodeToString(result)))
    }
    @Test fun `summary speeding ratio is time weighted`() {
        val trips = listOf(featureTrip(from, 10).copy(speedingRatio = .5), featureTrip(from, 30, 11).copy(speedingRatio = .1), featureTrip(from, 80, 12))
        assertEquals(.2, Habits.summary(trips, emptyList(), from, from.plusDays(7), KST).speedingRatio!!, 1e-9)
    }
    @Test fun `story names weekday not place`() {
        val trips = listOf(featureTrip(from, 10), featureTrip(from.plusDays(2), 50))
        assertEquals("수요일에 가장 오래 운전하셨어요", Habits.summary(trips, emptyList(), from, from.plusDays(7), KST).story)
    }
    @Test fun `summary uses half open dates and keeps missing data null`() {
        val trips = listOf(featureTrip(from.minusDays(1)), featureTrip(from.plusDays(7)))
        val result = Habits.summary(trips, emptyList(), from, from.plusDays(7), KST)
        assertEquals(0, result.trips)
        assertNull(result.story)
        assertNull(result.speedingRatio)
    }
    @Test fun `of preserves facts and summary excludes outside outings`() {
        val trip = featureTrip(from, 52).copy(announcedTurns = 4, signalMisses = 1, hardBrakes = 2, hardAccels = 3, night = true, rain = true)
        assertEquals(TripHabits(trip.startMs, 52, 3, 4, 2, 3, null, true, true), Habits.of(trip))
        val before = featureTrip(from.minusDays(1), 130)
        val outings = Outings.group(listOf(before, featureTrip(from, 120)), trip.endMs + 1_000_000)
        val result = Habits.summary(listOf(trip), outings, from, from.plusDays(7), KST)
        assertEquals(0, result.longStretches)
        assertEquals(0, result.calmTrips)
        assertEquals(1, result.nightTrips)
        assertEquals(3, result.hardAccels)
        assertEquals(3, result.signalKept)
        assertEquals(4, result.signalTotal)
    }
}
