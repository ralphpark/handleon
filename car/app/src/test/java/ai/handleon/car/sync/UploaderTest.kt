package ai.handleon.car.sync

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class UploaderTest {
    private val server = MockWebServer()
    @After fun close() = server.shutdown()

    @Test fun monthUploadHasNoLocation() = runTest {
        server.reply("", 201)
        Uploader(server.client()).upload("driver", longReport(), Mode.MAEUM, "car")
        val request = server.takeRequest()
        assertEquals("driver_id,month", request.requestUrl!!.queryParameter("on_conflict"))
        assertEquals("resolution=merge-duplicates,return=minimal", request.getHeader("Prefer"))
        val body = request.body.readUtf8()
        val rows = Json.parseToJsonElement(body).jsonArray
        assertEquals(1, rows.size)
        assertEquals(setOf("driver_id", "month", "mode", "stage", "domains", "top", "source"), rows[0].jsonObject.keys)
        listOf("lat", "lon", "cell", "home", "letter").forEach { assertFalse(it in body) }
        assertEquals("Bearer access-secret", request.getHeader("Authorization"))
    }

    @Test fun monthRowUsesLastMonthAndReportStage() {
        val row = monthRow("driver", longReport(), Mode.MAEUM, "replay")!!
        assertEquals("2026-09-01", row["month"]!!.jsonPrimitive.content)
        assertEquals("talk", row["stage"]!!.jsonPrimitive.content)
        assertEquals("replay", row["source"]!!.jsonPrimitive.content)
        assertEquals(2.0, row["domains"]!!.jsonObject["mobility"]!!.jsonPrimitive.double)   // 2.0 ÷ 임계 1.0
        assertEquals(1.5, row["domains"]!!.jsonObject["wayfinding"]!!.jsonPrimitive.double) // 3.0 ÷ 임계 2.0
        assertEquals("A", row["top"]!!.jsonArray[0].jsonObject["grade"]!!.jsonPrimitive.content)
    }

    @Test fun buildingRowFromInProgress() {
        val report = longReport().copy(months = emptyList(), inProgress = MonthProgress(YearMonth.of(2026, 10), 2))
        val row = monthRow("driver", report, Mode.MAEUM, "car")!!
        assertEquals("2026-10-01", row["month"]!!.jsonPrimitive.content)
        assertEquals("building", row["stage"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), row["domains"])
        assertEquals(JsonArray(emptyList()), row["top"])
        assertNull(monthRow("driver", report.copy(inProgress = null), Mode.MAEUM, "car"))
    }

    @Test fun nanDomainsAreDropped() {
        val report = longReport().copy(months = listOf(MonthResult(YearMonth.of(2026, 9),
            mapOf(Domain.MOBILITY to Double.NaN, Domain.WAYFINDING to Double.POSITIVE_INFINITY, Domain.OPERATION to 0.0), emptySet())))
        assertEquals(setOf("operation"), monthRow("driver", report, Mode.MAEUM, "car")!!["domains"]!!.jsonObject.keys)
        val noThreshold = report.copy(thresholds = mapOf(Domain.OPERATION to 0.0))
        assertEquals(JsonObject(emptyMap()), monthRow("driver", noThreshold, Mode.MAEUM, "car")!!["domains"])
    }

    @Test fun letterRequestSendsOnlyFeatureIds() = runTest {
        server.reply("[]"); server.reply("""{"letter":"핸들온 드림"}""")
        val report = longReport()
        Uploader(server.client()).letterFor("driver", monthRow("driver", report, Mode.MAEUM, "car")!!, report)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/functions/v1/monthly-letter", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("month", "mode", "stage", "top"), body.keys)
        val top = body["top"]!!.jsonArray
        top.forEach { assertEquals(setOf("feature", "direction", "grade"), it.jsonObject.keys) }
        assertEquals("down", top[0].jsonObject["direction"]!!.jsonPrimitive.content)
        assertEquals("up", top[1].jsonObject["direction"]!!.jsonPrimitive.content)
    }

    @Test fun existingServerLetterSkipsGeneration() = runTest {
        server.reply("""[{"letter":"저장된 편지"}]""")
        val report = longReport()
        assertEquals("저장된 편지", Uploader(server.client()).letterFor("driver", monthRow("driver", report, Mode.MAEUM, "car")!!, report))
        assertEquals(1, server.requestCount)
    }

    @Test fun buildingAndChangedSummaryDoNotGenerateOrReturnLetter() = runTest {
        val report = longReport().copy(months = emptyList(), inProgress = MonthProgress(YearMonth.of(2026, 10), 1))
        val uploader = Uploader(server.client())
        server.reply("[]")
        assertNull(uploader.letterFor("driver", monthRow("driver", report, Mode.MAEUM, "car")!!, report))
        assertEquals(1, server.requestCount)
        server.reply("[]"); server.reply("""{"error":"summary_changed"}""", 409)
        assertNull(uploader.letterFor("driver", monthRow("driver", longReport(), Mode.MAEUM, "car")!!, longReport()))
    }

    @Test fun dayRowsKeepLastFourteenAndSkipUnavailable() = runTest {
        val days = (1..20).map { DayResult(LocalDate.of(2026, 10, it), 1.0, if (it == 20) null else 2.0, 3.0, it == 19) }
        val report = ConditionReport(ConditionStatus.OK, days, emptyList())
        val rows = dayRows("driver", report)
        assertEquals(13, rows.size)
        assertEquals("2026-10-07", rows.first().jsonObject["day"]!!.jsonPrimitive.content)
        assertTrue(rows.last().jsonObject["alert"]!!.jsonPrimitive.boolean)
        server.reply("", 201)
        Uploader(server.client()).upload("driver", report)
        assertEquals("driver_id,day", server.takeRequest().requestUrl!!.queryParameter("on_conflict"))
    }
}
