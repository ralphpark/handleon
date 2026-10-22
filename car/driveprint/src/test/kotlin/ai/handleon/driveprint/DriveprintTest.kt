package ai.handleon.driveprint

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Random
import kotlin.test.*
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString

class DriveprintTest {
    @Test fun resetAfterLastDriveRestartsConditionImmediately() {
        val engine = Driveprint()
        val trips = replay("child")
        val today = LocalDate.of(2026, 10, 23)
        assertEquals(ConditionStatus.CAUTION, engine.conditionReport(trips, emptySet(), emptySet(), today).status)
        for (kind in listOf(ResetKind.CAR_CHANGED, ResetKind.ALL)) {
            val report = engine.conditionReport(trips, emptySet(), setOf(Reset(today, kind)), today)
            assertEquals(ConditionStatus.BUILDING, report.status, kind.name)
            assertTrue(report.top.isEmpty())
        }
        assertEquals(ConditionStatus.CAUTION, engine.conditionReport(trips, emptySet(),
            setOf(Reset(today, ResetKind.HOME_CHANGED)), today).status)
        assertEquals(ConditionStatus.CAUTION, engine.conditionReport(trips, emptySet(),
            setOf(Reset(today.plusDays(1), ResetKind.CAR_CHANGED)), today).status)
    }
    @Test fun homeChangeExcludesRadiusFromWeeklyOnset() {
        val from = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2027, 1, 1)
        val template = SyntheticTrips.decliningDriver(1, 99, 11).first()
        val dated = generateSequence(from) { it.plusDays(1) }.takeWhile { it < end }.map { day ->
            val start = day.atTime(12, 0).atZone(KST).toInstant().toEpochMilli()
            template.copy(startMs = start, endMs = start + 600_000,
                maxFromHomeKm = if (day < LocalDate.of(2026, 9, 1)) 30.0 else 3.0) to day
        }.toList()
        val report = LongReport(Stage.WATCH, listOf(MonthResult(YearMonth.of(2026, 12),
            mapOf(Domain.MOBILITY to 2.0), setOf(Domain.MOBILITY))), emptyMap(), emptyMap(), emptyList(), null,
            baselineWindows = listOf(FeatureId.M1, FeatureId.M7).associateWith {
                BaselineWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 7)) })
        val engine = Driveprint()
        assertNotNull(engine.onset(dated, report, end, emptySet()))
        assertNull(engine.onset(dated, report, end, emptySet(),
            setOf(Reset(LocalDate.of(2026, 9, 1), ResetKind.HOME_CHANGED))))
    }
    private fun replay(name: String): List<TripFeatures> = Json.decodeFromString(
        Json.parseToJsonElement(java.io.File("../app/src/main/assets/replay/$name.json").readText())
            .jsonObject.getValue("trips").toString())
    @Test fun fewTripWeekIsNotOnset() {
        val from = LocalDate.of(2027, 5, 8)
        val trips = SyntheticTrips.decliningDriver(18, 99, 11).filter {
            val day = java.time.Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate()
            day !in from..from.plusDays(5)
        }
        assertNull(Driveprint().longReport(trips, emptySet(), emptySet(), LocalDate.of(2027, 7, 1)).onsetWeeksAgo)
    }
    // 아버지 인물의 변화는 2025-11 에 시작해 6개월에 걸쳐 커진다. 변화 시점은 그 점진 구간 안(±4주)이면 맞다.
    @Test fun fatherOnsetWithinGradualChangeWindow() {
        val today = LocalDate.of(2026, 11, 1)
        val r = Driveprint().longReport(replay("father"), emptySet(), emptySet(), today)
        val weeks = { d: LocalDate -> java.time.temporal.ChronoUnit.WEEKS.between(d, today).toInt() }
        val earliest = weeks(LocalDate.of(2025, 11, 1)) + 4
        val latest = weeks(LocalDate.of(2026, 5, 1)) - 4
        assertNotNull(r.onsetWeeksAgo, "stage=${r.stage}, latest=${r.months.lastOrNull()}, thresholds=${r.thresholds}")
        assertTrue(r.onsetWeeksAgo!! in latest..earliest, "expected $latest..$earliest weeks, actual=${r.onsetWeeksAgo}")
    }
    @Test fun childSteeringTimes1_8GivesCaution() {
        val trips = replay("child").map {
            if (java.time.Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() >= LocalDate.of(2026, 10, 21))
                it.copy(steeringStd = it.steeringStd!! / 2 * 1.8) else it
        }
        val r = Driveprint().conditionReport(trips, emptySet(), emptySet(), LocalDate.of(2026, 10, 22))
        assertEquals(ConditionStatus.CAUTION, r.status, "lastDays=${r.days.takeLast(2)}, top=${r.top}")
    }
    @Test fun timelineWarmStartLaterScoresWayfinding() {
        val trips = SyntheticTrips.decliningDriver(14, 13, 11).map {
            if (java.time.Instant.ofEpochMilli(it.startMs).atZone(KST).monthValue <= 6 &&
                java.time.Instant.ofEpochMilli(it.startMs).atZone(KST).year == 2026) it.copy(source = Source.TIMELINE)
            else it.copy(source = Source.CAR)
        }
        val engine = Driveprint()
        val ready = engine.longReport(trips, emptySet(), emptySet(), LocalDate.of(2027, 1, 1))
        assertTrue(FeatureId.W3 in ready.baselines)
        assertTrue(ready.months.all { Domain.WAYFINDING !in it.domains })
        val changed = engine.longReport(trips, emptySet(), emptySet(), LocalDate.of(2027, 3, 1))
        assertTrue(Domain.WAYFINDING in changed.months.last().over)
    }
    @Test fun timelineDroppedOnCarDays() {
        val car = SyntheticTrips.decliningDriver(9, 99, 11).map { it.copy(source = Source.CAR) }
        val duplicate = car.map { it.copy(source = Source.TIMELINE, distanceKm = 200.0) }
        val engine = Driveprint()
        val today = LocalDate.of(2026, 10, 1)
        assertEquals(engine.longReport(car, emptySet(), emptySet(), today),
            engine.longReport(car + duplicate, emptySet(), emptySet(), today))
    }
    @Test fun monthStartDoesNotRaiseWatch() {
        val trips = SyntheticTrips.decliningDriver(19, 99, 11)
        val engine = Driveprint()
        val atStart = engine.longReport(trips, emptySet(), emptySet(), LocalDate.of(2027, 7, 1))
        val onSeventh = engine.longReport(trips, emptySet(), emptySet(), LocalDate.of(2027, 7, 7))
        assertEquals(Stage.STABLE, onSeventh.stage)
        assertEquals(atStart.months, onSeventh.months)
        assertEquals(YearMonth.of(2027, 6), onSeventh.months.last().month)
        assertEquals(YearMonth.of(2027, 7), onSeventh.inProgress!!.month)
        assertEquals(14, onSeventh.inProgress!!.trips)
    }
    @Test fun zeroTripActiveMonthCountsAsZero() {
        val trips = SyntheticTrips.decliningDriver(6, 99, 11)
        val r = Driveprint().longReport(trips, emptySet(), emptySet(), LocalDate.of(2026, 8, 7),
            activeMonths = setOf(YearMonth.of(2026, 7)))
        assertEquals(YearMonth.of(2026, 7), r.months.last().month)
        assertTrue(r.months.last().domains.getValue(Domain.MOBILITY) > 1.5)
        assertTrue(r.top.any { it.feature == FeatureId.M1 && it.z < 0 })
        assertFalse(r.top.any { it.feature in listOf(FeatureId.M4, FeatureId.M5, FeatureId.M6, FeatureId.M7) })
    }
    private val KST = ZoneId.of("Asia/Seoul")
    private object SyntheticTrips {
        fun decliningDriver(months: Int, onsetMonth: Int, seed: Long): List<TripFeatures> {
            val random = Random(seed)
            return (0 until months).flatMap { offset ->
                val month = YearMonth.of(2026, 1).plusMonths(offset.toLong())
                val decline = offset + 1 >= onsetMonth
                val count = if (decline) 30 else 60
                List(count) { i ->
                    val start = month.atDay(i % month.lengthOfMonth() + 1).atTime(12, i % 60)
                        .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
                    val guided = i % (if (decline) 2 else 10) == 0
                    TripFeatures(start, start + 600_000, Source.SIM, 10.0,
                        !decline && i % 5 == 0, false, false, (i % if (decline) 4 else 12).toLong(),
                        (if (decline) 10.0 else 30.0) + random.nextDouble() * 0.1,
                        true, guided, if (guided) 10.0 else 0.0, if (guided) (if (decline) 3 else 1) else 0,
                        if (decline) 2 else 4, if (decline) 8 else 6, 5, 0,
                        0.1, 0, 1.5, 2.0, 0, 0)
                }
            }
        }
    }
    @Test fun facadeReportsOnsetForSimulatedDecline() {
        val trips = SyntheticTrips.decliningDriver(months = 18, onsetMonth = 10, seed = 11)
        val r = Driveprint().longReport(trips, emptySet(), emptySet(), LocalDate.of(2027, 7, 1))
        assertEquals(YearMonth.of(2026, 7), r.baselineWindows.getValue(FeatureId.W3).toExclusive)
        assertEquals(Stage.TALK, r.stage); assertTrue(r.onsetWeeksAgo!! in 20..40)
    }
    @Test fun labeledDaysAreRemovedBeforeScoring() {
        val trips = SyntheticTrips.decliningDriver(18, 99, 11)
        val labels = (15..21).map { LocalDate.of(2027, 6, it) }.toSet()
        val withCold = trips.map { t ->
            if (java.time.Instant.ofEpochMilli(t.startMs).atZone(KST).toLocalDate() in labels)
                t.copy(reroutes = 100, guided = true, guidedKm = 1.0, steeringStd = 50.0) else t
        }
        val actual = Driveprint().longReport(withCold, labels, emptySet(), LocalDate.of(2027, 7, 1))
        val filtered = withCold.filter { java.time.Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() !in labels }
        assertEquals(Driveprint().longReport(filtered, labels, emptySet(), LocalDate.of(2027, 7, 1)), actual)
        assertEquals(Stage.STABLE, actual.stage)
    }
    @Test fun facadeIgnoresFutureTripsAndTimelineForCondition() {
        val trips = SyntheticTrips.decliningDriver(18, 10, 11)
        val today = LocalDate.of(2026, 1, 2)
        val engine = Driveprint()
        assertEquals(ConditionStatus.BUILDING, engine.conditionReport(
            trips.map { it.copy(source = Source.TIMELINE) }, emptySet(), emptySet(), today).status)
        assertTrue(engine.conditionReport(trips, emptySet(), emptySet(), today).days.all { it.day <= today })
        assertEquals(Stage.BUILDING, engine.longReport(trips, emptySet(), emptySet(), LocalDate.of(2026, 4, 1)).stage)
    }
    @Test fun emptyFacadesAreBuilding() {
        assertEquals(Stage.BUILDING, Driveprint().longReport(emptyList(), emptySet(), emptySet(), LocalDate.of(2026, 11, 1)).stage)
        assertEquals(ConditionStatus.BUILDING, Driveprint().conditionReport(emptyList(), emptySet(), emptySet(), LocalDate.of(2026, 10, 22)).status)
    }
}
