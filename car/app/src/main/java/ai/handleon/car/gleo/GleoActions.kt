package ai.handleon.car.gleo

import ai.handleon.car.ui.PendingConfirm
import ai.handleon.driveprint.*
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class GleoContext(val driving: Boolean, val today: List<TripHabits>, val week: HabitSummary?,
                       val promises: List<PromiseResult>, val readiness: Readiness?, val weekly: WeeklyStatus?,
                       val currentMonth: YearMonth? = null)

@Serializable
data class GleoCallback(val functionName: String, val status: String, val message: String, val data: String? = "[]")

object GleoActions {
    val FUNCTIONS = listOf("GET_DRIVING_SUMMARY", "GET_PROMISE_STATUS", "GET_CHANGE_STATUS", "MARK_OTHER_DRIVER", "PAUSE_FAMILY_SHARING")
    internal val EMPTY = GleoContext(true, emptyList(), null, emptyList(), null, null)
    internal const val SUMMARY = "Here are the driving summary counts."
    internal const val PROMISES = "Here are the weekly promise counts."
    internal const val CHANGE = "Here is the comparison preparation status."
    internal const val ASK = "Please confirm on the HandleOn screen after parking."
    internal const val INVALID = "The requested function or parameters are not supported."
    internal const val ERROR = "The driving summary is temporarily unavailable."
    internal const val BUSY = "Please complete the pending confirmation first."
    internal const val UNAVAILABLE = "There is no recent drive available for this confirmation."
    internal val MESSAGES = setOf(SUMMARY, PROMISES, CHANGE, ASK, INVALID, ERROR, BUSY, UNAVAILABLE)

    fun error(function: String) = GleoCallback(safeName(function), "error", ERROR)
    internal fun busy(function: String) = GleoCallback(safeName(function), "fail", BUSY)
    internal fun unavailable(function: String) = GleoCallback(safeName(function), "fail", UNAVAILABLE)
    private fun safeName(function: String) = function.takeIf { it in FUNCTIONS } ?: "UNKNOWN"

    /** Public output is an allowlist projection, never a serialized engine report or input echo. */
    fun respond(function: String, paramsJson: String, ctx: GleoContext): Pair<GleoCallback, PendingConfirm?> {
        val params = runCatching { parameters(paramsJson) }.getOrNull()
        val valid = function in FUNCTIONS && params != null && if (function == "GET_DRIVING_SUMMARY") {
            params.keys == setOf("period") && (params["period"] as? JsonPrimitive)?.let { it.isString && it.content in setOf("today", "week") } == true
        } else params.isEmpty()
        if (!valid) return GleoCallback(safeName(function), "fail", INVALID) to null
        val pending = when (function) {
            "MARK_OTHER_DRIVER" -> PendingConfirm.MarkOtherDriver
            "PAUSE_FAMILY_SHARING" -> PendingConfirm.PauseSharing
            else -> null
        }
        if (pending != null) return GleoCallback(function, "asking", ASK) to pending
        val rows = when (function) {
            "GET_DRIVING_SUMMARY" -> {
                val summary = if (params!!.getValue("period").jsonPrimitive.content == "today") today(ctx.today)
                    else ctx.week?.let { summary(it.trips.toLong(), it.minutes.toLong(), it.signalKept.toLong(), it.signalTotal.toLong(),
                        it.hardBrakes.toLong(), it.hardAccels.toLong(), it.nightTrips.toLong(), 10080) }
                listOfNotNull(summary)
            }
            "GET_PROMISE_STATUS" -> ctx.promises.distinctBy { it.promise }.sortedBy { it.promise.ordinal }.map {
                val prefix = when (it.promise) { Promise.NO_NIGHT -> "no_night"; Promise.REST_2H -> "rest_2h"; Promise.NO_RAIN -> "no_rain" }
                val kept = it.kept.coerceIn(0, 100000)
                val total = (kept.toLong() + it.broken.coerceAtLeast(0)).coerceAtMost(100000).toInt()
                buildJsonObject { put("${prefix}_kept", kept.coerceAtMost(total)); put("${prefix}_total", total) }
            }
            else -> listOf(buildJsonObject {
                put("status", when (ctx.weekly) { WeeklyStatus.OK -> "ok"; WeeklyStatus.FLAGGED -> "flagged"; else -> "building" })
                ctx.readiness?.let { ready ->
                    put("weeks_left", ready.weeklyWeeksLeft.coerceIn(0, 520))
                    if (ready.monthlyFrom != null && ctx.currentMonth != null) {
                        put("months_left", ChronoUnit.MONTHS.between(ctx.currentMonth, ready.monthlyFrom).coerceIn(0, 120))
                    } else if (ready.monthlyFrom == null && ready.monthlyTripsLeft <= 0) put("months_left", 0)
                }
            })
        }
        val message = when (function) { "GET_DRIVING_SUMMARY" -> SUMMARY; "GET_PROMISE_STATUS" -> PROMISES; else -> CHANGE }
        return GleoCallback(function, "success", message, JsonArray(rows).toString()) to null
    }

    private fun parameters(raw: String): JsonObject {
        require(raw.length <= 4096)
        var value = Json.parseToJsonElement(raw)
        // The guide shows a direct object, an actionParameters envelope, and a JSON-encoded string.
        if (value is JsonPrimitive && value.isString) value = Json.parseToJsonElement(value.content)
        val objectValue = value.jsonObject
        return if (objectValue.keys == setOf("actionParameters")) objectValue.getValue("actionParameters").jsonObject else objectValue
    }
    private fun today(values: List<TripHabits>): JsonObject = summary(values.size.toLong(), values.sumOf { it.minutes.coerceAtLeast(0).toLong() },
        values.sumOf { it.signalKept.coerceAtLeast(0).toLong() }, values.sumOf { it.signalTotal.coerceAtLeast(0).toLong() },
        values.sumOf { it.hardBrakes.coerceAtLeast(0).toLong() }, values.sumOf { it.hardAccels.coerceAtLeast(0).toLong() },
        values.count { it.night }.toLong(), 1440)
    private fun summary(trips: Long, minutes: Long, kept: Long, total: Long, brakes: Long, accels: Long, night: Long, maxMinutes: Int): JsonObject {
        val count = trips.coerceIn(0, 200)
        val signals = total.coerceIn(0, 100000)
        return buildJsonObject {
            put("trips", count); put("minutes", minutes.coerceIn(0, maxMinutes.toLong()))
            put("signal_kept", kept.coerceIn(0, signals)); put("signal_total", signals)
            put("hard_brakes", brakes.coerceIn(0, 100000)); put("hard_accels", accels.coerceIn(0, 100000))
            put("night_trips", night.coerceIn(0, count))
        }
    }
}
