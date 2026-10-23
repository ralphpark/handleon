package ai.handleon.car.sync

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.Copy
import ai.handleon.driveprint.LongReport
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
        putJsonObject("domains") { last?.domains?.forEach { (domain, value) ->
            if (value.isFinite()) put(domain.name.lowercase(Locale.ROOT), value)
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

fun letterBody(row: JsonObject, report: LongReport): JsonObject = buildJsonObject {
    listOf("month", "mode", "stage").forEach { put(it, row.getValue(it)) }
    putJsonArray("top") {
        if (row.getValue("stage").jsonPrimitive.content != "building") report.top.take(3).forEach { c -> add(buildJsonObject {
            put("feature", c.feature.name); put("direction", if (c.z < 0) "down" else "up"); put("grade", c.feature.grade.name)
        }) }
    }
}

class Uploader(private val client: SupabaseClient) {
    suspend fun upload(driverId: String, report: LongReport, mode: Mode, source: String): JsonObject? {
        val row = monthRow(driverId, report, mode, source) ?: return null
        client.upsert("monthly_summaries", JsonArray(listOf(row)), "driver_id,month")
        return row
    }
    suspend fun upload(driverId: String, report: ConditionReport) {
        val rows = dayRows(driverId, report)
        if (rows.isNotEmpty()) client.upsert("daily_conditions", rows, "driver_id,day")
    }
    suspend fun letterFor(driverId: String, row: JsonObject, report: LongReport): String? = try {
        val month = row.getValue("month").jsonPrimitive.content
        val existing = client.select("monthly_summaries", "select=letter&driver_id=eq.$driverId&month=eq.$month")
            .firstOrNull()?.jsonObject?.get("letter")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        existing ?: if (row.getValue("stage").jsonPrimitive.content == "building") null
            else client.invoke("monthly-letter", letterBody(row, report))["letter"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
