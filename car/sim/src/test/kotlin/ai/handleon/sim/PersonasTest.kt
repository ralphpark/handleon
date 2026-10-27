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
    @Test fun `sudden export uses the versioned replay schema and fixed persona`() {
        val out = Files.createTempDirectory(File("build").toPath(), "sudden-test-").toFile()
        try {
            val file = File(out, "nested/sudden.json")
            main(arrayOf("--export-sudden", file.path))
            val json = file.readText()
            assertEquals(setOf("version", "persona", "trips"), Json.parseToJsonElement(json).jsonObject.keys)
            assertEquals(Replay(1, "sudden", Personas.sudden()), Json.decodeFromString<Replay>(json))
            assertTrue(file.length() in 1L..1_000_000L)
        } finally { out.deleteRecursively() }
    }
    @Test fun `new car persona interventions decline`() {
        val trips = Personas.newCar()
        val weeks = Adapt.weekly(trips, ZoneId.of("Asia/Seoul"))
        assertEquals(12, weeks.size)
        assertTrue(weeks.zipWithNext().all { (a, b) -> a.weekStart.plusWeeks(1) == b.weekStart })
        val early = weeks.take(3).map { it.lka + it.fca }.average()
        val late = weeks.takeLast(3).map { it.lka + it.fca }.average()
        assertTrue(early > 2 * late, "first three=$early, last three=$late")
        assertTrue(weeks.all { it.per100km != null })
        assertTrue(trips.all { it.source == Source.SIM && it.endMs > it.startMs && it.lkaEvents >= 0 && it.fcaEvents >= 0 })
        assertEquals(trips.sortedBy { it.startMs }, trips)
        assertEquals(trips, Personas.newCar(3))
        assertEquals(Personas.newCar(4), Personas.newCar(4))
        assertNotEquals(trips, Personas.newCar(4))
    }

    @Test fun `new car export uses the versioned replay schema`() {
        val out = Files.createTempDirectory(File("build").toPath(), "new-car-test-").toFile()
        try {
            val file = File(out, "nested/new_car.json")
            main(arrayOf("--export-new-car", file.path))
            assertTrue(file.length() in 1L..1_000_000L)
            val json = file.readText()
            assertEquals(setOf("version", "persona", "trips"), Json.parseToJsonElement(json).jsonObject.keys)
            assertEquals(Replay(1, "new_car", Personas.newCar()), Json.decodeFromString<Replay>(json))
        } finally {
            out.deleteRecursively()
        }
    }

    @Test fun fatherReachesTalkInSummer2026() {
        val r = Driveprint().longReport(Personas.father(), emptySet(), emptySet(), YearMonth.of(2026, 10).plusMonths(1).atDay(1))
        assertEquals(Stage.TALK, r.stage)
        val firstTalk = (1..24).map { YearMonth.of(2024, 11).plusMonths(it.toLong() - 1) }
            .first { Driveprint().longReport(Personas.father(), emptySet(), emptySet(), it.plusMonths(1).atDay(1)).stage == Stage.TALK }
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
