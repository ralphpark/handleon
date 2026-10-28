package ai.handleon.car.replay

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

class ReplayScenarioTest {
    private fun load(p: ReplayPersona) = ReplayParser.parse(File("src/main/assets/${p.asset}").readText())

    @Test fun `three primary personas remain available with the existing child replay`() {
        assertEquals(listOf(ReplayPersona.FATHER, ReplayPersona.NEW_CAR, ReplayPersona.SUDDEN), ReplayPersona.forMode(Mode.MAEUM))
        assertEquals(ReplayPersona.CHILD, ReplayPersona.forMode(Mode.CONDITION).first())
        assertTrue(ReplayPersona.forMode(Mode.CONDITION).containsAll(ReplayPersona.forMode(Mode.MAEUM)))
    }
    @Test fun `new car starts with one drive and never reveals future trips`() = runTest {
        val data = load(ReplayPersona.NEW_CAR)
        val frames = ReplayController(data.trips, Driveprint()).frames(ReplayPersona.NEW_CAR).toList()
        assertEquals(13, frames.size)
        assertTrue(frames.first().firstDrive)
        assertEquals(1, frames.first().trips.size)
        assertEquals(Habits.line(Habits.of(data.trips.minBy { it.startMs })), frames.first().tripLine)
        assertEquals(data.trips.size, frames.last().trips.size)
        frames.forEach { f ->
            assertTrue(f.trips.all { it.endMs <= f.throughMs && it.startMs <= f.throughMs })
            assertTrue(f.weekly.weeks.all { it.weekStart.plusWeeks(1) <= f.today })
        }
        val adapt = frames.last().adapt
        assertEquals(12, adapt.size)
        assertTrue(adapt.first().per100km!! > adapt.last().per100km!!)
    }
    @Test fun `sudden replay shows a weekly change within three weeks of the drop`() = runTest {
        val frames = ReplayController(load(ReplayPersona.SUDDEN).trips, Driveprint()).frames(ReplayPersona.SUDDEN).toList()
        val drop = LocalDate.of(2026, 6, 1)
        val first = frames.first { f -> f.weekly.weeks.any { it.flagged && it.weekStart >= drop } }
        assertTrue(first.today <= drop.plusWeeks(3))
        assertEquals(WeeklyStatus.FLAGGED, first.weekly.status)
        assertTrue(first.weekly.weeks.last().over.isNotEmpty())
    }
    @Test fun `weekly change lines stay on screen long enough to read`() = runTest {
        for (persona in listOf(ReplayPersona.SUDDEN, ReplayPersona.FATHER)) {
            val timed = mutableListOf<Pair<Long, ReplayFrame>>()
            ReplayController(load(persona).trips, Driveprint()).frames(persona).collect { timed += currentTime to it }
            val flagged = timed.indices.filter { timed[it].second.weekly.status == WeeklyStatus.FLAGGED }
            if (persona == ReplayPersona.SUDDEN) assertEquals(3, flagged.size)
            flagged.filter { it + 1 < timed.size }.forEach { i ->
                assertTrue(timed[i + 1].first - timed[i].first >= persona.stepMs + ReplayController.WEEKLY_HOLD_MS)
            }
            println("REPLAY ${persona.id} flagged=${flagged.size} total=${currentTime}ms")
        }
    }
    @Test fun `father scenario keeps 24 monthly frames and the final talk scene`() = runTest {
        val frames = ReplayController(load(ReplayPersona.FATHER).trips, Driveprint()).frames(ReplayPersona.FATHER).toList()
        assertEquals(24, frames.size)
        assertEquals(Stage.TALK, frames.last().longReport.stage)
        assertEquals(LocalDate.of(2026, 11, 1), frames.last().today)
    }
    @Test fun `replay boundaries reject car and timeline records`() {
        val t = load(ReplayPersona.FATHER).trips.first()
        for (source in listOf(Source.CAR, Source.TIMELINE)) {
            val trips = listOf(t, t.copy(source = source))
            assertFailsWith<IllegalArgumentException> { ReplayParser.parse(Json.encodeToString(ReplayData(1, "father", trips))) }
            assertFailsWith<IllegalArgumentException> { ReplayController(trips, Driveprint()) }
        }
    }
}
