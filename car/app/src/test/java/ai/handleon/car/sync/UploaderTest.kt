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

    @Test fun patternRowHasOnlyAggregateKeys() = runTest {
        val month = PatternMonth(YearMonth.of(2026, 10), true, 3, 61, 2,
            0.333, 0.333, null, null, null, null, null, null)
        val row = patternRows("driver", listOf(month), "car").single().jsonObject
        // The provided contract enumerates 15 keys (despite saying 16).
        assertEquals(setOf("driver_id", "month", "partial", "trips", "drive_minutes", "drive_days", "night_ratio",
            "long_ratio", "radius_ratio", "places_ratio", "usual_trips", "usual_minutes", "usual_night_ratio", "usual_long_ratio", "source"), row.keys)
        // 'long_ratio' is explicitly allowed; 'lon' as a coordinate key is not.
        assertTrue(row.keys.none { key -> key in setOf("lat", "lon") || listOf("latitude", "longitude", "cell", "home", "km").any { it in key } })
        for (key in listOf("radius_ratio", "places_ratio", "usual_trips", "usual_minutes", "usual_night_ratio", "usual_long_ratio")) assertEquals(JsonNull, row[key])
        assertEquals("2026-10-01", row["month"]!!.jsonPrimitive.content)
        server.reply("", 201)
        Uploader(server.client()).uploadPatterns("driver", listOf(month), "car")
        val request = server.takeRequest()
        assertEquals("/rest/v1/monthly_patterns", request.requestUrl!!.encodedPath)
        assertEquals("driver_id,month", request.requestUrl!!.queryParameter("on_conflict"))
        assertEquals(row, Json.parseToJsonElement(request.body.readUtf8()).jsonArray.single())
    }

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
    private val monday = LocalDate.of(2026, 10, 19)
    private fun summary() = HabitSummary(monday, monday.plusWeeks(1), 4, 200, 3, 4, 1, 0, 3, 0, 1, null, "월요일에 가장 오래 운전하셨어요")
    @Test fun `weekly rows skip excluded weeks`() {
        val weeks = (0L..14L).map { WeekResult(monday.minusWeeks(it), emptySet(), false, it == 1L) }
        val r = WeeklyReport(WeeklyStatus.OK, weeks.reversed(), null, 0, emptyList())
        val rows = weeklyChangeRows("driver", r, "car")
        assertEquals(11, rows.size)
        assertEquals(monday.minusWeeks(11).toString(), rows.first().jsonObject["week_start"]!!.jsonPrimitive.content)
        assertTrue(rows.all { it.jsonObject["domains"]!!.jsonArray.isEmpty() })
    }
    @Test fun `flagged baseline exclusions still upload with exact domains and top contract`() {
        val week = WeekResult(monday, setOf(Domain.MOBILITY), true, true)
        val r = WeeklyReport(WeeklyStatus.FLAGGED, listOf(week.copy(weekStart = monday.minusWeeks(1)), week), null, 0,
            listOf(Contribution(FeatureId.M1, -1.0, 1.0), Contribution(FeatureId.W2, 0.0, 1.0)))
        val rows = weeklyChangeRows("driver", r, "car")
        val row = rows.last().jsonObject
        assertEquals(setOf("driver_id", "week_start", "flagged", "domains", "top", "source"), row.keys)
        assertEquals("MOBILITY", row["domains"]!!.jsonArray.single().jsonPrimitive.content)
        val top = row["top"]!!.jsonArray
        assertEquals(setOf("feature", "direction"), top.first().jsonObject.keys)
        assertEquals("down", top[0].jsonObject["direction"]!!.jsonPrimitive.content)
        assertEquals("up", top[1].jsonObject["direction"]!!.jsonPrimitive.content)
        assertTrue(rows.first().jsonObject["top"]!!.jsonArray.isEmpty())
    }
    @Test fun `habit rows keep kept less than or equal to total`() {
        val bad = summary().copy(trips = 999, minutes = Int.MAX_VALUE, signalKept = 100, signalTotal = -1,
            hardBrakes = -5, hardAccels = -2, calmTrips = 999, nightTrips = 999, longStretches = -1)
        val row = weeklyHabitRows("driver", listOf(bad to listOf(PromiseResult(Promise.NO_NIGHT, Int.MAX_VALUE, Int.MAX_VALUE))), "car").single().jsonObject
        assertEquals(200, row["trips"]!!.jsonPrimitive.int)
        assertEquals(10080, row["minutes"]!!.jsonPrimitive.int)
        assertEquals(0, row["signal_kept"]!!.jsonPrimitive.int)
        assertEquals(0, row["signal_total"]!!.jsonPrimitive.int)
        assertEquals(200, row["calm_trips"]!!.jsonPrimitive.int)
        assertEquals(200, row["night_trips"]!!.jsonPrimitive.int)
        assertTrue(row["promises_kept"]!!.jsonPrimitive.long <= row["promises_total"]!!.jsonPrimitive.long)
        assertEquals(0, row["hard_brakes"]!!.jsonPrimitive.int)
    }
    @Test fun `habit rows carry no coordinates`() {
        val row = weeklyHabitRows("driver", listOf(summary() to emptyList()), "car").single().jsonObject
        assertEquals(setOf("driver_id", "week_start", "trips", "minutes", "signal_kept", "signal_total", "hard_brakes", "hard_accels",
            "calm_trips", "long_stretches", "night_trips", "promises_kept", "promises_total", "story", "source"), row.keys)
        assertTrue(row.values.all { it is JsonPrimitive && (it.longOrNull != null || (it.isString && it.content.length <= 60)) })
    }
    @Test fun `upload story selects only server templates and ignores display story`() {
        val h = summary().copy(story = "임의 장소 37.500 127.100")
        assertEquals("이번 주 4번 운전했어요", uploadHabitStory(h, emptyList()))
        assertEquals("", uploadHabitStory(h.copy(trips = 0), emptyList()))
        assertEquals("방향지시등을 모두 켰어요", uploadHabitStory(h.copy(signalKept = 4), emptyList()))
        assertEquals("운전 약속을 모두 지켰어요", uploadHabitStory(h, listOf(PromiseResult(Promise.NO_NIGHT, 1, 0))))
    }
    @Test fun `habit rows keep only eight recent weeks`() {
        val weeks = (0L..10L).map { summary().copy(from = monday.minusWeeks(it), toExclusive = monday.minusWeeks(it).plusWeeks(1)) to emptyList<PromiseResult>() }
        assertEquals(8, weeklyHabitRows("driver", weeks, "car").size)
    }
    @Test fun `weekly upserts use week conflict keys`() = runTest {
        val server = MockWebServer()
        try {
            server.reply("", 201); server.reply("", 201)
            val uploader = Uploader(server.client())
            uploader.uploadWeeklyChanges("driver", WeeklyReport(WeeklyStatus.OK, listOf(WeekResult(monday, emptySet(), false, false)), null, 0, emptyList()), "car")
            uploader.uploadWeeklyHabits("driver", listOf(summary() to emptyList()), "car")
            for (table in listOf("weekly_changes", "weekly_habits")) {
                val request = server.takeRequest()
                assertEquals("/rest/v1/$table", request.requestUrl!!.encodedPath)
                assertEquals("driver_id,week_start", request.requestUrl!!.queryParameter("on_conflict"))
            }
        } finally { server.shutdown() }
    }
}
