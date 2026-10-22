package ai.handleon.sim

import ai.handleon.driveprint.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class PersonasTest {
    @Test fun fatherReachesTalkInSummer2026() {
        val r = Driveprint().longReport(Personas.father(), emptySet(), emptySet(), YearMonth.of(2026, 10))
        assertEquals(Stage.TALK, r.stage)
        val firstTalk = (1..24).map { YearMonth.of(2024, 11).plusMonths(it.toLong() - 1) }
            .first { Driveprint().longReport(Personas.father(), emptySet(), emptySet(), it).stage == Stage.TALK }
        assertTrue(firstTalk in YearMonth.of(2026, 6)..YearMonth.of(2026, 9))
    }
    @Test fun childEndsWithCaution() = assertEquals(ConditionStatus.CAUTION,
        Driveprint().conditionReport(Personas.child(), emptySet(), emptySet(), LocalDate.of(2026, 10, 22)).status)

    @Test fun childHasSixtyCommuteDaysAndCautionNeedsSteeringIncrease() {
        val zone = ZoneId.of("Asia/Seoul")
        fun day(t: TripFeatures) = Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate()
        val trips = Personas.child()
        val first = LocalDate.of(2026, 8, 24)
        val last = LocalDate.of(2026, 10, 22)
        assertEquals(120, trips.size)
        assertEquals((0L until 60).map { first.plusDays(it) }.toSet(), trips.map(::day).toSet())
        assertTrue(trips.groupBy(::day).values.all { it.size == 2 })
        assertTrue(trips.all { it.source == Source.SIM && it.endMs > it.startMs })
        assertTrue(trips.zipWithNext().all { (a, b) -> a.endMs < b.startMs })
        val withoutIncrease = trips.map {
            if (day(it) >= last.minusDays(1)) it.copy(steeringStd = it.steeringStd?.div(2)) else it
        }
        assertEquals(ConditionStatus.OK, Driveprint().conditionReport(withoutIncrease, emptySet(), emptySet(), last).status)
        assertEquals(ConditionStatus.OK, Driveprint().conditionReport(trips, emptySet(), emptySet(), last.minusDays(1)).status)
    }

    @Test fun personaCliWritesVersionedRoundTrippableReplayFiles() {
        val out = Files.createTempDirectory(File("build").toPath(), "replay-test-").toFile()
        try {
            for ((name, trips) in listOf("father" to Personas.father(), "child" to Personas.child())) {
                main(arrayOf("--persona", name, "--out", out.path))
                val file = File(out, "$name.json")
                assertTrue(file.length() in 1L..1_000_000L)
                val json = file.readText()
                val fields = Json.parseToJsonElement(json).jsonObject
                assertEquals(setOf("version", "persona", "trips"), fields.keys)
                assertEquals(1, fields.getValue("version").jsonPrimitive.int)
                assertEquals(Replay(1, name, trips), Json.decodeFromString<Replay>(json))
            }
        } finally {
            out.deleteRecursively()
        }
    }
}
