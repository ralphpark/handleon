package ai.handleon.driveprint

import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class WeeklyTrackTest {
    private val start = LocalDate.of(2026, 1, 5)
    private fun w(i: Int, radius: Double = 100.0, trips: Int = 10, labeled: Boolean = false, holiday: Boolean = false) =
        WeekInput(start.plusWeeks(i.toLong()), mapOf(FeatureId.M7 to radius), trips, labeled, holiday)
    private fun base(n: Int = 8) = (0 until n).map { w(it) }
    @Test fun `building until four eligible weeks`() {
        val r = WeeklyTrack.evaluate(base(3))
        assertEquals(WeeklyStatus.BUILDING, r.status)
        assertEquals(1, r.weeksUntilReady)
        assertEquals(0, WeeklyTrack.evaluate(base(4)).weeksUntilReady)
    }
    @Test fun `provisional comparison starts after four baseline weeks`() {
        val r = WeeklyTrack.evaluate(base(4) + w(4, 0.0) + w(5, 0.0))
        assertEquals(setOf(Domain.MOBILITY), r.weeks[4].over)
        assertFalse(r.weeks[4].flagged)
        assertTrue(r.weeks[5].flagged)
    }
    @Test fun `reset during current week clears readiness immediately`() {
        val today = LocalDate.of(2026, 5, 5)
        val r = Driveprint().weeklyReport(weeklyTrips(4), emptySet(), setOf(Reset(today, ResetKind.ALL)), today)
        assertEquals(WeeklyStatus.BUILDING, r.status)
        assertEquals(4, r.weeksUntilReady)
        assertNull(r.baselineFrom)
    }
    @Test fun `thin weeks do not count toward baseline`() {
        val r = WeeklyTrack.evaluate(base(3) + w(3, trips = 2))
        assertEquals(WeeklyStatus.BUILDING, r.status)
        assertEquals(1, r.weeksUntilReady)
        assertTrue(r.weeks.last().excluded)
    }
    @Test fun `thin changed weeks can be compared but never become baseline`() {
        val r = WeeklyTrack.evaluate(base() + w(8, 0.0, trips = 2) + w(9, 0.0, trips = 2))
        assertTrue(r.weeks.takeLast(2).all { it.excluded })
        assertTrue(r.weeks.last().flagged)
    }
    @Test fun `one domain over two weeks flags`() {
        val r = WeeklyTrack.evaluate(base() + w(8, 0.0) + w(9, 0.0))
        assertEquals(WeeklyStatus.FLAGGED, r.status)
        assertEquals(setOf(Domain.MOBILITY), r.weeks.last().over)
        assertTrue(r.weeks.last().flagged)
        assertTrue(r.weeks.last().excluded)
        assertEquals(FeatureId.M7, r.top.first().feature)
        assertTrue(r.top.first().z < 0)
        assertEquals(r, Json.decodeFromString<WeeklyReport>(Json.encodeToString(r)))
    }
    @Test fun `one week over does not flag`() {
        val r = WeeklyTrack.evaluate(base() + w(8, 0.0))
        assertEquals(WeeklyStatus.OK, r.status)
        assertFalse(r.weeks.last().flagged)
    }
    @Test fun `cooldown suppresses repeat for four weeks`() {
        val r = WeeklyTrack.evaluate(base() + (8..14).map { w(it, 0.0) })
        assertEquals(listOf(start.plusWeeks(9), start.plusWeeks(14)), r.weeks.filter { it.flagged }.map { it.weekStart })
    }
    @Test fun `holiday week never flags and never enters baseline`() {
        val building = WeeklyTrack.evaluate(base(3) + w(3, holiday = true))
        assertEquals(1, building.weeksUntilReady)
        assertTrue(building.weeks.last().excluded)
        val r = WeeklyTrack.evaluate(base() + w(8, 0.0) + w(9, 0.0, holiday = true) + w(10, 0.0))
        assertTrue(r.weeks[9].excluded)
        assertTrue(r.weeks[9].over.isEmpty())
        assertFalse(r.weeks.any { it.flagged })
    }
    @Test fun `baseline stays fixed while values drift slowly`() {
        val r = WeeklyTrack.evaluate(base() + (8..20).map { w(it, 100.0 - (it - 7) * 4) })
        assertEquals(start, r.baselineFrom)
        assertTrue(r.weeks.last().over.contains(Domain.MOBILITY))
    }
    @Test fun `reanchors after 26 weeks`() {
        assertEquals(start, WeeklyTrack.evaluate(base(26)).baselineFrom)
        assertEquals(start.plusWeeks(18), WeeklyTrack.evaluate(base(27)).baselineFrom)
    }
    @Test fun `gate false blocks a statistical flag`() {
        val r = WeeklyTrack.evaluate(base() + w(8, 0.0) + w(9, 0.0), gate = ForecastGate { _, _ -> false })
        assertEquals(WeeklyStatus.OK, r.status)
        assertFalse(r.weeks.last().flagged)
        assertEquals(setOf(Domain.MOBILITY), r.weeks.last().over)
    }
    @Test fun `null gate falls back to statistics`() {
        val input = base() + w(8, 0.0) + w(9, 0.0)
        assertTrue(WeeklyTrack.evaluate(input, gate = ForecastGate { _, _ -> null }).weeks.last().flagged)
        assertTrue(WeeklyTrack.evaluate(input).weeks.last().flagged)
    }
    @Test fun `timeline trips are ignored by weeklyReport`() {
        val trips = weeklyTrips(8).map { it.copy(source = Source.TIMELINE) }
        val r = Driveprint().weeklyReport(trips, emptySet(), emptySet(), LocalDate.of(2026, 6, 1))
        assertEquals(WeeklyStatus.BUILDING, r.status)
        assertEquals(4, r.weeksUntilReady)
        assertTrue(r.weeks.isEmpty())
    }
    @Test fun `readiness counts weeks and months left`() {
        val trips = weeklyTrips(3)
        val today = LocalDate.of(2026, 4, 27)
        val engine = Driveprint()
        val weekly = engine.weeklyReport(trips, emptySet(), emptySet(), today)
        val ready = engine.readiness(trips, weekly, today)
        assertEquals(1, ready.weeklyWeeksLeft)
        assertEquals(YearMonth.of(2026, 11), ready.monthlyFrom)
        assertEquals(41, ready.monthlyTripsLeft)
    }
    @Test fun `imported timeline history lets the monthly track compare right away`() {
        val timeline = (0L..11L).flatMap { month -> (1..10).map { day ->
            featureTrip(YearMonth.of(2025, 4).plusMonths(month).atDay(day)).copy(source = Source.TIMELINE)
        } }
        val trips = timeline + weeklyTrips(3)
        val today = LocalDate.of(2026, 4, 27)
        val engine = Driveprint()
        assertNotEquals(Stage.BUILDING, engine.longReport(trips, emptySet(), emptySet(), today).stage)
        val r = engine.readiness(trips, engine.weeklyReport(trips, emptySet(), emptySet(), today), today)
        assertNull(r.monthlyFrom)
        assertEquals(0, r.monthlyTripsLeft)
        assertEquals(1, r.weeklyWeeksLeft)
    }
    @Test fun `weeks start at KST Monday and current week is not judged`() {
        val sunday = featureTrip(LocalDate.of(2026, 4, 12), 20, 23)
        val monday = featureTrip(LocalDate.of(2026, 4, 13), 20, 0)
        val r = Driveprint().weeklyReport(listOf(sunday, monday), emptySet(), emptySet(), LocalDate.of(2026, 4, 13))
        assertEquals(listOf(LocalDate.of(2026, 4, 6)), r.weeks.map { it.weekStart })
    }
    @Test fun `labels and weekday holidays exclude real weeks`() {
        val trips = (0L..13).map { featureTrip(LocalDate.of(2026, 5, 4).plusDays(it)) }
        val r = Driveprint().weeklyReport(trips, setOf(LocalDate.of(2026, 5, 12)), emptySet(), LocalDate.of(2026, 5, 18))
        assertEquals(2, r.weeks.size)
        assertTrue(r.weeks.all { it.excluded })
        assertEquals(4, r.weeksUntilReady)
    }
    @Test fun `missing or labeled weeks break consecutive evidence`() {
        assertFalse(WeeklyTrack.evaluate(base() + w(8, 0.0) + w(10, 0.0)).weeks.any { it.flagged })
        assertFalse(WeeklyTrack.evaluate(base() + w(8, 0.0) + w(9, 0.0, labeled = true) + w(10, 0.0)).weeks.any { it.flagged })
    }
    @Test fun `resets rebuild baseline without mixing the reset week`() {
        for (kind in ResetKind.entries) {
            val reset = Reset(start.plusWeeks(8).plusDays(2), kind)
            val r = WeeklyTrack.evaluate(base(12), setOf(reset))
            assertEquals(1, r.weeksUntilReady)
            assertEquals(WeeklyStatus.BUILDING, r.status)
            assertEquals(start.plusWeeks(9), r.baselineFrom)
        }
    }
    @Test fun `forecast must repeat the same feature in the same domain`() {
        val input = (base() + w(8, 0.0) + w(9, 0.0)).map { it.copy(values = it.values + (FeatureId.M5 to if (it.weekStart < start.plusWeeks(8)) 20.0 else 0.0)) }
        val r = WeeklyTrack.evaluate(input, gate = ForecastGate { day, f ->
            (day == start.plusWeeks(8) && f == FeatureId.M7) || (day == start.plusWeeks(9) && f == FeatureId.M5)
        })
        assertFalse(r.weeks.last().flagged)
        assertTrue(WeeklyTrack.evaluate(input, gate = ForecastGate { _, _ -> true }).weeks.last().flagged)
    }
    @Test fun `empty readiness has no invented start month`() {
        val engine = Driveprint()
        val today = LocalDate.of(2026, 4, 6)
        assertEquals(Readiness(4, null, 50), engine.readiness(emptyList(), WeeklyTrack.evaluate(emptyList()), today))
    }
    @Test fun `monthly readiness honors all reset instead of old history`() {
        val history = (0L..11L).flatMap { month -> (1..10).map { day ->
            featureTrip(YearMonth.of(2025, 4).plusMonths(month).atDay(day))
        } }
        val trips = history + weeklyTrips(3)
        val today = LocalDate.of(2026, 4, 27)
        val resets = setOf(Reset(LocalDate.of(2026, 3, 1), ResetKind.ALL))
        val engine = Driveprint()
        val weekly = engine.weeklyReport(trips, emptySet(), resets, today)
        assertEquals(Stage.BUILDING, engine.longReport(trips, emptySet(), resets, today).stage)
        val ready = engine.readiness(trips, weekly, today, emptySet(), resets)
        assertEquals(YearMonth.of(2026, 10), ready.monthlyFrom)
        assertEquals(31, ready.monthlyTripsLeft)
    }
    @Test fun `monthly readiness excludes labeled history from readiness and count`() {
        val days = (0L..11L).flatMap { month -> (1..10).map { day -> YearMonth.of(2025, 4).plusMonths(month).atDay(day) } }
        val trips = days.map { featureTrip(it) } + weeklyTrips(3)
        val labels = days.toSet()
        val today = LocalDate.of(2026, 4, 27)
        val engine = Driveprint()
        val weekly = engine.weeklyReport(trips, labels, emptySet(), today)
        assertEquals(Stage.BUILDING, engine.longReport(trips, labels, emptySet(), today).stage)
        val ready = engine.readiness(trips, weekly, today, labels, emptySet())
        assertEquals(YearMonth.of(2026, 11), ready.monthlyFrom)
        assertEquals(41, ready.monthlyTripsLeft)
    }
    @Test fun `monthly readiness does not count a labeled recent trip`() {
        val trips = weeklyTrips(3)
        val labels = setOf(LocalDate.of(2026, 4, 6))
        val today = LocalDate.of(2026, 4, 27)
        val engine = Driveprint()
        val weekly = engine.weeklyReport(trips, labels, emptySet(), today)
        val ready = engine.readiness(trips, weekly, today, labels, emptySet())
        assertEquals(42, ready.monthlyTripsLeft)
    }
    private fun weeklyTrips(count: Int) = (0 until count).flatMap { i -> (0..2).map { day ->
        featureTrip(LocalDate.of(2026, 4, 6).plusWeeks(i.toLong()).plusDays(day.toLong()))
    } }
}
