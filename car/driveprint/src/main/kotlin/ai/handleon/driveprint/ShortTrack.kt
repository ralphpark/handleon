package ai.handleon.driveprint

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

data class DayInput(val day: LocalDate, val values: Map<FeatureId, Double>, val trips: Int, val labeled: Boolean)
@Serializable enum class ConditionStatus { BUILDING, OK, CAUTION, HOLD }
@Serializable
data class DayResult(
    @Serializable(with = LocalDateSerializer::class) val day: LocalDate,
    val index: Double?, val ewma: Double?, val threshold: Double?, val alert: Boolean,
)
@Serializable
data class ConditionReport(val status: ConditionStatus, val days: List<DayResult>, val top: List<Contribution>)

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object ShortTrack {
    fun evaluate(days: List<DayInput>, resetDays: Set<LocalDate> = emptySet()): ConditionReport {
        val driving = days.filter { it.trips > 0 }.sortedBy { it.day }
        require(driving.map { it.day }.distinct().size == driving.size) { "One input per driving day is required" }
        val history = mutableListOf<Pair<DayInput, DayResult>>()
        var previousDay: LocalDate? = null
        var activeFrom = driving.firstOrNull()?.day ?: return ConditionReport(ConditionStatus.BUILDING, emptyList(), emptyList())
        var ewma: Double? = null
        var previousOver = false
        var status = ConditionStatus.BUILDING
        var top = emptyList<Contribution>()
        for (input in driving) {
            val previous = previousDay
            if (previous != null && (ChronoUnit.DAYS.between(previous, input.day) >= 14 ||
                    resetDays.any { it > previous && it <= input.day })) {
                activeFrom = input.day
                ewma = null
                previousOver = false
            }
            val eligible = history.filter { (day, result) ->
                day.day >= activeFrom && day.day >= input.day.minusDays(28) &&
                    day.day < input.day && !day.labeled && !result.alert
            }
            val baselines = CONDITION_FEATURES.mapNotNull { f ->
                Stats.baseline(eligible.mapNotNull { it.first.values[f] })?.let { f to it }
            }.toMap()
            val ready = eligible.size >= 7 && baselines.isNotEmpty()
            val raw = if (!ready) emptyMap() else input.values.mapNotNull { (f, value) ->
                baselines[f]?.let { f to Stats.z(value, it) }
            }.toMap()
            val index = Scoring.domainIndex(raw.mapValues { (f, z) -> Scoring.change(f, z) }, Domain.OPERATION)
            val threshold = if (ready) Scoring.threshold(eligible.mapNotNull { it.second.ewma }) else null
            if (index != null) ewma = ewma?.let { 0.25 * index + 0.75 * it } ?: index
            val over = index != null && threshold != null && ewma!! > threshold && !input.labeled
            val alert = over && previousOver
            val result = DayResult(input.day, index, if (index == null) null else ewma, threshold, alert)
            status = when {
                !ready -> ConditionStatus.BUILDING
                index == null -> ConditionStatus.HOLD
                alert -> ConditionStatus.CAUTION
                else -> ConditionStatus.OK
            }
            top = Scoring.top(raw)
            history.add(input to result)
            previousOver = over
            previousDay = input.day
        }
        return ConditionReport(status, history.map { it.second }, top)
    }
}
