package ai.handleon.car.sync

import ai.handleon.driveprint.HolidayModel
import ai.handleon.driveprint.Source
import ai.handleon.driveprint.TripFeatures
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class FedModel(val round: Int, val weights: List<Double>)
data class FedVirtual(val tag: String, val weeks: List<Pair<LocalDate, Double>>)

fun parseFedModel(raw: String?): FedModel? = runCatching {
    val obj = Json.parseToJsonElement(raw ?: return null).jsonObject
    require(obj.keys == setOf("round", "weights"))
    val round = obj.getValue("round").jsonPrimitive.let { require(!it.isString); it.int }
    val weights = obj.getValue("weights").jsonArray.map { value ->
        value.jsonPrimitive.let { require(!it.isString); it.double.also { number -> require(number.isFinite()) } }
    }
    require(round >= 0 && weights.size == HolidayModel.SIZE)
    FedModel(round, weights)
}.getOrNull()

/** Null means callers must retain the engine's original default path. */
fun fedEffect(raw: String?): ((LocalDate) -> Double)? {
    val model = parseFedModel(raw) ?: return null
    if (model.round == 0 || model.weights.all { it == 0.0 }) return null
    val weights = model.weights.toDoubleArray()
    return { day -> HolidayModel.predict(weights, day).takeIf(Double::isFinite) ?: 0.0 }
}

private fun monday(day: LocalDate) = day.minusDays(day.dayOfWeek.value - 1L)

/** Historical API name; the engine normalizes raw counts itself. Unobserved weeks are not fabricated. */
fun weeklyRatios(trips: List<TripFeatures>, zone: ZoneId, nowMs: Long = System.currentTimeMillis()): List<Pair<LocalDate, Double>> {
    val current = monday(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
    return trips.asSequence().filter { it.source == Source.CAR && it.startMs <= it.endMs && it.endMs <= nowMs }
        .groupingBy { monday(Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()) }.eachCount()
        .filterKeys { it < current }.toSortedMap().entries.toList().takeLast(520).map { it.key to it.value.toDouble() }
}

internal fun completedFedWeeks(weeks: List<Pair<LocalDate, Double>>, nowMs: Long): List<Pair<LocalDate, Double>> {
    require(weeks.map { it.first }.distinct().size == weeks.size)
    require(weeks.all { it.first.dayOfWeek.value == 1 && it.second.isFinite() && it.second >= 0 })
    val current = monday(Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
    return weeks.filter { it.first < current }.sortedBy { it.first }.takeLast(520)
}

fun parseFedVirtuals(raw: String): List<FedVirtual> {
    val doc = Json.parseToJsonElement(raw).jsonObject
    require(doc.getValue("version").jsonPrimitive.int == 1)
    val clients = doc.getValue("clients").jsonArray.map { item ->
        val obj = item.jsonObject
        FedVirtual(obj.getValue("tag").jsonPrimitive.content, obj.getValue("weeks").jsonArray.map { row ->
            val week = row.jsonObject
            LocalDate.parse(week.getValue("week_start").jsonPrimitive.content) to week.getValue("trips").jsonPrimitive.double
        })
    }
    require(clients.map { it.tag }.sorted() == listOf("virtual-1", "virtual-2"))
    return clients.sortedBy { it.tag }
}
