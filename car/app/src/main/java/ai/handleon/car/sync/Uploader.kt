package ai.handleon.car.sync

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.Copy
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.PatternMonth
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

fun monthRow(driverId: String, report: LongReport, mode: Mode, source: String): JsonObject? {
    require(source == "car" || source == "replay")
    val last = report.months.lastOrNull()
    val month = last?.month ?: report.inProgress?.month ?: return null
    return buildJsonObject {
        put("driver_id", driverId); put("month", month.atDay(1).toString())
        put("mode", mode.name.lowercase(Locale.ROOT))
        put("stage", if (last == null) "building" else report.stage.name.lowercase(Locale.ROOT))
        // 영역 지수 ÷ 개인 임계값: 1 을 넘으면 평소 범위 밖. 휴대폰은 임계값을 모르므로 비율로 보낸다.
        putJsonObject("domains") { last?.domains?.forEach { (domain, value) ->
            val ratio = report.thresholds[domain]?.takeIf { it.isFinite() && it > 0 }?.let { value / it }
            if (ratio != null && ratio.isFinite()) put(domain.name.lowercase(Locale.ROOT), Math.round(ratio * 1000) / 1000.0)
        } }
        putJsonArray("top") { if (last != null) report.top.take(3).forEach { c -> add(buildJsonObject {
            put("feature", c.feature.name); put("text", Copy.line(c)); put("grade", c.feature.grade.name)
        }) } }
        put("source", source)
    }
}

fun dayRows(driverId: String, report: ConditionReport): JsonArray = buildJsonArray {
    report.days.takeLast(14).forEach { day ->
        val ewma = day.ewma?.takeIf(Double::isFinite) ?: return@forEach
        val threshold = day.threshold?.takeIf(Double::isFinite) ?: return@forEach
        add(buildJsonObject {
            put("driver_id", driverId); put("day", day.day.toString()); put("ewma", ewma)
            put("threshold", threshold); put("alert", day.alert)
        })
    }
}

fun patternRows(driverId: String, months: List<PatternMonth>, source: String): JsonArray {
    require(source == "car" || source == "replay")
    return buildJsonArray { months.forEach { month -> add(buildJsonObject {
        put("driver_id", driverId); put("month", month.month.atDay(1).toString())
        put("partial", month.partial); put("trips", month.trips)
        put("drive_minutes", month.driveMinutes); put("drive_days", month.driveDays)
        put("night_ratio", month.nightRatio); put("long_ratio", month.longRatio)
        put("radius_ratio", month.radiusRatio); put("places_ratio", month.placesRatio)
        put("usual_trips", month.usualTrips); put("usual_minutes", month.usualMinutes)
        put("usual_night_ratio", month.usualNightRatio); put("usual_long_ratio", month.usualLongRatio)
        put("source", source)
    }) } }
}

fun letterBody(row: JsonObject, report: LongReport): JsonObject = buildJsonObject {
    listOf("month", "mode", "stage").forEach { put(it, row.getValue(it)) }
    putJsonArray("top") {
        if (row.getValue("stage").jsonPrimitive.content != "building") report.top.take(3).forEach { c -> add(buildJsonObject {
            put("feature", c.feature.name); put("direction", if (c.z < 0) "down" else "up"); put("grade", c.feature.grade.name)
        }) }
    }
}

class Uploader(private val client: SupabaseClient) {
    suspend fun uploadWeeklyChanges(driverId: String, report: ai.handleon.driveprint.WeeklyReport, source: String,
                                    allowed: () -> Boolean = { true }) =
        uploadWeeklyChanges(weeklyChangeRows(driverId, report, source), allowed)
    suspend fun uploadWeeklyChanges(rows: JsonArray, allowed: () -> Boolean = { true }) {
        if (rows.isNotEmpty()) client.upsert("weekly_changes", rows, "driver_id,week_start", allowed)
    }
    suspend fun uploadWeeklyHabits(driverId: String, weeks: List<Pair<ai.handleon.driveprint.HabitSummary, List<ai.handleon.driveprint.PromiseResult>>>, source: String,
                                   allowed: () -> Boolean = { true }) =
        uploadWeeklyHabits(weeklyHabitRows(driverId, weeks, source), allowed)
    suspend fun uploadWeeklyHabits(rows: JsonArray, allowed: () -> Boolean = { true }) {
        if (rows.isNotEmpty()) client.upsert("weekly_habits", rows, "driver_id,week_start", allowed)
    }
    suspend fun reportOuting(outing: ai.handleon.driveprint.Outing) {
        client.rpc("report_drive_end", DriveNotices.reportBody(outing))
    }
    suspend fun uploadPatterns(driverId: String, months: List<PatternMonth>, source: String, allowed: () -> Boolean = { true }) {
        val rows = patternRows(driverId, months, source)
        if (rows.isNotEmpty()) client.upsert("monthly_patterns", rows, "driver_id,month", allowed)
    }
    suspend fun upload(driverId: String, report: LongReport, mode: Mode, source: String, allowed: () -> Boolean = { true }): JsonObject? {
        val row = monthRow(driverId, report, mode, source) ?: return null
        client.upsert("monthly_summaries", JsonArray(listOf(row)), "driver_id,month", allowed)
        return row
    }
    suspend fun upload(driverId: String, report: ConditionReport) {
        val rows = dayRows(driverId, report)
        if (rows.isNotEmpty()) client.upsert("daily_conditions", rows, "driver_id,day")
    }
    suspend fun letterFor(driverId: String, row: JsonObject, report: LongReport, allowed: () -> Boolean = { true }): String? {
        if (!allowed()) return null
        return try {
            val month = row.getValue("month").jsonPrimitive.content
            val existing = client.select("monthly_summaries", "select=letter&driver_id=eq.$driverId&month=eq.$month", allowed)
                .firstOrNull()?.jsonObject?.get("letter")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            existing ?: if (!allowed() || row.getValue("stage").jsonPrimitive.content == "building") null
                else client.invoke("monthly-letter", letterBody(row, report), allowed)["letter"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (aborted: RequestAbortedException) { throw aborted }
        catch (_: Exception) { null }
    }
}

/** Engine excluded means excluded from baseline learning, so a flagged result is still publishable. */
fun weeklyChangeRows(driverId: String, report: ai.handleon.driveprint.WeeklyReport, source: String): JsonArray {
    require(source in setOf("car", "replay"))
    val latest = report.weeks.maxOfOrNull { it.weekStart } ?: return JsonArray(emptyList())
    val features = setOf("M1", "M2", "M3", "M4", "M5", "M6", "M7", "W2", "W3", "W4", "O1", "O2", "O7", "O8", "O9")
    return buildJsonArray {
        report.weeks.sortedBy { it.weekStart }.distinctBy { it.weekStart }.filter {
            it.weekStart >= latest.minusWeeks(11) && (!it.excluded || it.flagged)
        }.forEach { week ->
            require(week.weekStart.dayOfWeek == java.time.DayOfWeek.MONDAY)
            require(!week.flagged || week.over.isNotEmpty())
            add(buildJsonObject {
                put("driver_id", driverId); put("week_start", week.weekStart.toString()); put("flagged", week.flagged)
                putJsonArray("domains") { week.over.sortedBy { it.ordinal }.forEach { add(it.name) } }
                putJsonArray("top") {
                    // The engine exposes contributions only for the latest completed week.
                    if (week.weekStart == latest) report.top.filter { it.feature.name in features }.take(3).forEach { c ->
                        add(buildJsonObject { put("feature", c.feature.name); put("direction", if (c.z < 0) "down" else "up") })
                    }
                }
                put("source", source)
            })
        }
    }
}

/** A server allowlist projection, deliberately independent of the on-screen weekday story. */
fun uploadHabitStory(h: ai.handleon.driveprint.HabitSummary, promises: List<ai.handleon.driveprint.PromiseResult>): String = when {
    h.trips <= 0 -> ""
    promises.any { it.kept > 0 } && promises.all { it.kept >= 0 && it.broken == 0 } -> "운전 약속을 모두 지켰어요"
    h.signalTotal > 0 && h.signalKept == h.signalTotal -> "방향지시등을 모두 켰어요"
    else -> "이번 주 ${h.trips.coerceIn(0, 200)}번 운전했어요"
}
fun weeklyHabitRows(driverId: String, weeks: List<Pair<ai.handleon.driveprint.HabitSummary, List<ai.handleon.driveprint.PromiseResult>>>, source: String): JsonArray {
    require(source in setOf("car", "replay"))
    val latest = weeks.maxOfOrNull { it.first.from } ?: return JsonArray(emptyList())
    return buildJsonArray {
        weeks.sortedBy { it.first.from }.distinctBy { it.first.from }.filter { it.first.from >= latest.minusWeeks(7) }.forEach { (h, promises) ->
            require(h.from.dayOfWeek == java.time.DayOfWeek.MONDAY)
            val trips = h.trips.coerceIn(0, 200)
            val signalTotal = h.signalTotal.coerceAtLeast(0)
            val promiseTotal = promises.sumOf { it.kept.coerceAtLeast(0).toLong() + it.broken.coerceAtLeast(0) }.coerceIn(0, Int.MAX_VALUE.toLong())
            val promiseKept = promises.sumOf { it.kept.coerceAtLeast(0).toLong() }.coerceIn(0, promiseTotal)
            add(buildJsonObject {
                put("driver_id", driverId); put("week_start", h.from.toString())
                put("trips", trips); put("minutes", h.minutes.coerceIn(0, 10080))
                put("signal_kept", h.signalKept.coerceIn(0, signalTotal)); put("signal_total", signalTotal)
                put("hard_brakes", h.hardBrakes.coerceAtLeast(0)); put("hard_accels", h.hardAccels.coerceAtLeast(0))
                put("calm_trips", h.calmTrips.coerceIn(0, trips)); put("long_stretches", h.longStretches.coerceAtLeast(0))
                put("night_trips", h.nightTrips.coerceIn(0, trips))
                put("promises_kept", promiseKept); put("promises_total", promiseTotal)
                put("story", uploadHabitStory(h, promises)); put("source", source)
            })
        }
    }
}
