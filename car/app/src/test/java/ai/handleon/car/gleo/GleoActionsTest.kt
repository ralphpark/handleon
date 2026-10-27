package ai.handleon.car.gleo

import ai.handleon.car.ui.PendingConfirm
import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class GleoActionsTest {
    private val day = LocalDate.of(2026, 10, 27)
    private val context = GleoContext(false,
        listOf(TripHabits(1793070000000, 20, 3, 4, 1, 0, null, false, false),
            TripHabits(1793073600000, 30, 2, 2, 0, 2, .5, true, false)),
        HabitSummary(day, day.plusWeeks(1), 8, 120, 10, 12, 2, 1, 5, 0, 2, null, "private place or family name"),
        listOf(PromiseResult(Promise.NO_NIGHT, 3, 1)), Readiness(2, YearMonth.of(2027, 3), 4),
        WeeklyStatus.BUILDING, currentMonth = YearMonth.of(2026, 10))
    private fun response(name: String, params: String = "{}", ctx: GleoContext = context) = GleoActions.respond(name, params, ctx).first
    private fun row(callback: GleoCallback) = Json.parseToJsonElement(callback.data!!).jsonArray.single().jsonObject

    @Test fun `summary returns numbers only`() {
        val callback = response("GET_DRIVING_SUMMARY", """{"period":"today"}""")
        assertEquals("success", callback.status)
        val row = row(callback)
        assertEquals(setOf("trips", "minutes", "signal_kept", "signal_total", "hard_brakes", "hard_accels", "night_trips"), row.keys)
        assertEquals(2, row["trips"]!!.jsonPrimitive.int)
        assertEquals(50, row["minutes"]!!.jsonPrimitive.int)
        assertEquals(5, row["signal_kept"]!!.jsonPrimitive.int)
        assertEquals(6, row["signal_total"]!!.jsonPrimitive.int)
        assertTrue(row.values.all { it.jsonPrimitive.intOrNull != null })
        val week = row(response("GET_DRIVING_SUMMARY", """{"period":"week"}"""))
        assertEquals(120, week["minutes"]!!.jsonPrimitive.int)
        assertFalse(week.toString().contains("private"))
    }
    @Test fun `state changes ask for on-screen confirm`() {
        for (driving in listOf(false, true)) for ((name, expected) in listOf(
            "MARK_OTHER_DRIVER" to PendingConfirm.MarkOtherDriver, "PAUSE_FAMILY_SHARING" to PendingConfirm.PauseSharing)) {
            val (callback, action) = GleoActions.respond(name, "{}", context.copy(driving = driving))
            assertEquals("asking", callback.status)
            assertEquals("Please confirm on the HandleOn screen after parking.", callback.message)
            assertEquals(expected, action)
            assertEquals("[]", callback.data)
        }
    }
    @Test fun `unknown function fails politely`() {
        val callback = response("PRIVATE_LOCATION_37_127")
        assertEquals("fail", callback.status)
        assertEquals("UNKNOWN", callback.functionName)
        assertFalse(callback.toString().contains("PRIVATE_LOCATION"))
    }
    @Test fun `messages are english sentences`() {
        for (name in GleoActions.FUNCTIONS + "UNKNOWN") {
            val callback = response(name, if (name == "GET_DRIVING_SUMMARY") """{"period":"today"}""" else "{}")
            assertTrue(callback.message.endsWith('.'))
            assertTrue(callback.message.all { it.code in 32..126 })
        }
    }
    @Test fun `documented parameter wrappers normalize and unrecognized parameters fail`() {
        val direct = """{"period":"week"}"""
        val expected = response("GET_DRIVING_SUMMARY", direct)
        assertEquals(expected, response("GET_DRIVING_SUMMARY", """{"actionParameters":$direct}"""))
        assertEquals(expected, response("GET_DRIVING_SUMMARY", JsonPrimitive(direct).toString()))
        for (bad in listOf("null", "[]", "broken", "{}", """{"period":"month"}""", """{"period":1}""",
            """{"period":"today","lat":37}""", """{"actionParameters":{},"period":"today"}""", "x".repeat(4097))) {
            assertEquals("fail", response("GET_DRIVING_SUMMARY", bad).status, bad.take(50))
        }
        assertEquals("fail", response("MARK_OTHER_DRIVER", """{"confirm":true}""").status)
    }
    @Test fun `public numbers are bounded and kept never exceeds total`() {
        val bad = context.copy(today = listOf(TripHabits(1, Int.MAX_VALUE, Int.MAX_VALUE, -1, -1, 0, Double.NaN, false, false)))
        val row = row(response("GET_DRIVING_SUMMARY", """{"period":"today"}""", bad))
        assertEquals(1440, row["minutes"]!!.jsonPrimitive.int)
        assertEquals(0, row["signal_kept"]!!.jsonPrimitive.int)
        assertEquals(0, row["hard_brakes"]!!.jsonPrimitive.int)
    }
    @Test fun `promise and comparison responses contain only allowed facts`() {
        val promise = row(response("GET_PROMISE_STATUS"))
        assertEquals(setOf("no_night_kept", "no_night_total"), promise.keys)
        assertEquals(3, promise["no_night_kept"]!!.jsonPrimitive.int)
        assertEquals(4, promise["no_night_total"]!!.jsonPrimitive.int)
        val change = row(response("GET_CHANGE_STATUS"))
        assertEquals(setOf("status", "weeks_left", "months_left"), change.keys)
        assertEquals("building", change["status"]!!.jsonPrimitive.content)
        assertEquals(2, change["weeks_left"]!!.jsonPrimitive.int)
        assertEquals(5, change["months_left"]!!.jsonPrimitive.int)
        val unknown = row(response("GET_CHANGE_STATUS", ctx = context.copy(readiness = null, weekly = null)))
        assertEquals(setOf("status"), unknown.keys)
        assertEquals("[]", response("GET_DRIVING_SUMMARY", """{"period":"week"}""", context.copy(week = null)).data)
    }
}
