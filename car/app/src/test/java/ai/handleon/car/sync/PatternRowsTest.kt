package ai.handleon.car.sync

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.PatternMonth
import ai.handleon.driveprint.Stage
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import kotlin.test.*

class PatternRowsTest {
    private val server = MockWebServer()
    @After fun close() = server.shutdown()

    private val keys = setOf("driver_id", "month", "partial", "trips", "drive_minutes", "drive_days", "night_ratio",
        "long_ratio", "radius_ratio", "places_ratio", "usual_trips", "usual_minutes", "usual_night_ratio", "usual_long_ratio", "source")
    private val nullable = listOf("night_ratio", "long_ratio", "radius_ratio", "places_ratio",
        "usual_trips", "usual_minutes", "usual_night_ratio", "usual_long_ratio")
    private val full = PatternMonth(YearMonth.of(2026, 1), false, 12, 345, 9, 0.25, 0.083, 1.5, 0.75, 14, 400, 0.2, 0.1)
    private val empty = PatternMonth(YearMonth.of(2026, 2), true, 0, 0, 0, null, null, null, null, null, null, null, null)

    @Test fun everyNullableValueIsSentAsExplicitNull() {
        val row = patternRows("driver", listOf(empty), "replay").single().jsonObject
        assertEquals(keys, row.keys)
        for (key in nullable) assertEquals(JsonNull, row[key], key)
        assertEquals(JsonPrimitive(0), row["trips"]); assertEquals(JsonPrimitive(true), row["partial"])
        assertEquals("replay", row["source"]!!.jsonPrimitive.content)
    }

    @Test fun filledRowKeepsIntegersIntegralAndRatiosAsIs() {
        val row = patternRows("driver", listOf(full), "car").single().jsonObject
        assertEquals(keys, row.keys)
        assertEquals("2026-01-01", row["month"]!!.jsonPrimitive.content)
        for ((key, text) in listOf("trips" to "12", "drive_minutes" to "345", "drive_days" to "9",
            "usual_trips" to "14", "usual_minutes" to "400")) assertEquals(text, row[key].toString(), key)
        for ((key, value) in listOf("night_ratio" to 0.25, "long_ratio" to 0.083, "radius_ratio" to 1.5, "places_ratio" to 0.75,
            "usual_night_ratio" to 0.2, "usual_long_ratio" to 0.1)) assertEquals(value, row[key]!!.jsonPrimitive.double, key)
        assertEquals(JsonPrimitive(false), row["partial"])
    }

    @Test fun rowsKeepInputOrderAndEachUsesFirstDayOfItsMonth() {
        val rows = patternRows("driver-1", listOf(empty, full, full.copy(month = YearMonth.of(2025, 12))), "car")
        assertEquals(listOf("2026-02-01", "2026-01-01", "2025-12-01"), rows.map { it.jsonObject["month"]!!.jsonPrimitive.content })
        assertTrue(rows.all { it.jsonObject["driver_id"]!!.jsonPrimitive.content == "driver-1" })
    }

    @Test fun sourceMustBeCarOrReplayEvenWithoutMonths() {
        assertTrue(patternRows("driver", emptyList(), "car").isEmpty())
        assertTrue(patternRows("driver", emptyList(), "replay").isEmpty())
        for (source in listOf("sim", "timeline", "CAR", "Replay", "", " car")) {
            assertFailsWith<IllegalArgumentException>(source) { patternRows("driver", emptyList(), source) }
            assertFailsWith<IllegalArgumentException>(source) { patternRows("driver", listOf(full), source) }
        }
        val building = LongReport(Stage.BUILDING, emptyList(), emptyMap(), emptyMap(), emptyList(), null)
        assertFailsWith<IllegalArgumentException> { monthRow("driver", building, Mode.MAEUM, "sim") }
    }

    @Test fun noMonthsMeansNoUploadRequest() = runTest {
        Uploader(server.client()).uploadPatterns("driver", emptyList(), "car")
        assertEquals(0, server.requestCount)
    }
}
