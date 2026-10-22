package ai.handleon.car.replay

import ai.handleon.driveprint.ConditionStatus
import ai.handleon.driveprint.Driveprint
import ai.handleon.driveprint.Stage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayControllerTest {
    @Test fun fatherReplayEmits24MonthsEndingInTalk() = runTest {
        val reports = ReplayController(load("father").trips, Driveprint()).monthly().toList()
        assertEquals(24, reports.size)
        assertEquals(Stage.BUILDING, reports.first().stage)
        assertEquals(Stage.TALK, reports.last().stage)
        assertEquals(60_000L, currentTime)
    }

    @Test fun childReplayEndsInCaution() = runTest {
        val reports = ReplayController(load("child").trips, Driveprint()).daily().toList()
        assertEquals(60, reports.size)
        assertEquals(ConditionStatus.BUILDING, reports.first().status)
        assertEquals(ConditionStatus.CAUTION, reports.last().status)
        assertEquals(LocalDate.of(2026, 10, 22), reports.last().days.last().day)
        assertEquals(30_000L, currentTime)
    }

    @Test fun monthlyIncludesEmptyMonthsAndUsesDepartureInSeoul() = runTest {
        val template = load("father").trips.first()
        val january = Instant.parse("2026-01-31T14:59:00Z").toEpochMilli()
        val march = Instant.parse("2026-02-28T15:01:00Z").toEpochMilli()
        val reports = ReplayController(listOf(
            template.copy(startMs = march, endMs = march + 60_000),
            template.copy(startMs = january, endMs = january + 120_000)), Driveprint()).monthly().toList()
        assertEquals(3, reports.size)
        assertEquals(7_500L, currentTime)
    }

    @Test fun dailyGroupsByDepartureDateInSeoul() = runTest {
        val template = load("child").trips.first()
        val first = Instant.parse("2026-01-31T15:01:00Z").toEpochMilli()
        val second = Instant.parse("2026-02-01T14:59:00Z").toEpochMilli()
        val reports = ReplayController(listOf(
            template.copy(startMs = second, endMs = second + 120_000),
            template.copy(startMs = first, endMs = first + 60_000)), Driveprint()).daily().toList()
        assertEquals(1, reports.size)
        assertEquals(LocalDate.of(2026, 2, 1), reports.single().days.single().day)
        assertEquals(500L, currentTime)
    }

    @Test fun emptyReplayEmitsNothing() = runTest {
        val controller = ReplayController(emptyList(), Driveprint())
        assertTrue(controller.monthly().toList().isEmpty())
        assertTrue(controller.daily().toList().isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test fun parserPreservesEnvelopeAndTrips() {
        val data = load("father")
        assertEquals(1, data.version)
        assertEquals("father", data.persona)
        assertEquals(1231, data.trips.size)
        assertEquals(1730430365000L, data.trips.first().startMs)
    }

    @Test fun parserRejectsUnsupportedVersion() {
        assertFailsWith<IllegalArgumentException> {
            ReplayParser.parse("""{"version":2,"persona":"father","trips":[]}""")
        }
    }

    private fun load(persona: String) = ReplayParser.parse(File("src/main/assets/replay/$persona.json").readText())
}
