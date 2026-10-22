package ai.handleon.sim

import ai.handleon.driveprint.*
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlinx.serialization.Serializable

@Serializable
data class PersonaOutcome(
    val id: Int,
    @Serializable(with = YearMonthSerializer::class) val declineOnset: YearMonth?,
    @Serializable(with = YearMonthSerializer::class) val firstTalk: YearMonth?,
    val detected: Boolean, val falseAlarmEntries: Int, val personYears: Double,
    val leadMonths: Double?, val censored: Boolean,
    val stages: List<Stage>, val monthly: List<MonthResult>,
)
@Serializable
data class CohortMetrics(
    val sensitivity: Double, val falseAlarmsPerPersonYear: Double, val medianLeadMonths: Double?,
    val normalWatchMonthRatio: Double, val normalWatchPersonRatio: Double,
)
@Serializable
data class CohortResult(
    val sensitivity: Double, val falseAlarmsPerPersonYear: Double,
    val medianLeadMonths: Double?, val perPersona: List<PersonaOutcome>,
    val normalWatchMonthRatio: Double, val normalWatchPersonRatio: Double,
    val ruleComparison: Map<String, CohortMetrics>,
)

object CohortRun {
    val START: YearMonth = YearMonth.of(2024, 11)
    const val DEFAULT_MONTHS = 30

    fun run(drivers: Int = 200, months: Int = DEFAULT_MONTHS, seed: Long = 7,
            lifestyleVariant: Boolean = false): CohortResult {
        require(drivers >= 2 && months >= 7)
        val random = Random(seed)
        val outcomes = (0 until drivers).map { id ->
            val onset = if (id < drivers / 2) null else START.plusMonths(9L + random.nextInt(7))
            val p = Persona(id, onset, 30 + random.nextDouble() * 40, 8 + random.nextInt(13),
                10 + random.nextDouble() * 30, random.nextLong())
            val generated = Generator.trips(p, START, months)
            val trips = if (lifestyleVariant && id < (drivers / 2 * .2).toInt()) lifestyleTrips(generated, p.seed) else generated
            val engine = Driveprint()
            val reports = (0 until months).map {
                engine.longReport(trips, emptySet(), emptySet(), START.plusMonths(it.toLong() + 1).atDay(1))
            }
            val stages = reports.map { it.stage }
            outcome(id, onset, stages, reports.last().months)
        }
        val comparison = Rule.entries.associate { rule -> rule.name to metrics(outcomes.map { p ->
            outcome(p.id, p.declineOnset, RuleComparison.stages(p.monthly, START, months, rule), p.monthly)
        }) }
        val measured = metrics(outcomes)
        check(comparison.getValue("A") == measured) { "Rule A must reproduce the engine results" }
        return CohortResult(measured.sensitivity, measured.falseAlarmsPerPersonYear, measured.medianLeadMonths,
            outcomes, measured.normalWatchMonthRatio, measured.normalWatchPersonRatio, comparison)
    }

    internal fun metrics(outcomes: List<PersonaOutcome>): CohortMetrics {
        val changed = outcomes.filter { it.declineOnset != null }
        val normal = outcomes.filter { it.declineOnset == null }
        val lead = changed.mapNotNull { it.leadMonths }
        val scoredMonths = normal.sumOf { it.monthly.size }
        require(changed.isNotEmpty() && normal.isNotEmpty() && scoredMonths > 0)
        val watchCounts = normal.map { p -> p.monthly.count {
            p.stages[ChronoUnit.MONTHS.between(START, it.month).toInt()] == Stage.WATCH
        } }
        return CohortMetrics(changed.count { it.detected }.toDouble() / changed.size,
            normal.sumOf { it.falseAlarmEntries } / normal.sumOf { it.personYears },
            lead.takeIf { it.isNotEmpty() }?.let(Stats::median),
            watchCounts.sum().toDouble() / scoredMonths, watchCounts.count { it > 0 }.toDouble() / normal.size)
    }

    internal fun lifestyleTrips(trips: List<TripFeatures>, seed: Long): List<TripFeatures> = trips.groupBy {
        YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(ZoneId.of("Asia/Seoul")))
    }.flatMap { (month, selected) ->
        if (month < START.plusMonths(12)) selected else {
            val shuffled = selected.toMutableList()
            java.util.Collections.shuffle(shuffled, Random(seed xor month.toString().hashCode().toLong()))
            shuffled.take((selected.size * .8).toInt())
        }
    }.sortedBy { it.startMs }

    internal fun outcome(id: Int, onset: YearMonth?, stages: List<Stage>, monthly: List<MonthResult>): PersonaOutcome {
        val end = START.plusMonths(stages.size - 1L)
        val firstTalk = stages.indexOfFirst { it == Stage.TALK }.takeIf { it >= 0 }?.let { START.plusMonths(it.toLong()) }
        // An earlier TALK must not hide a later detection inside the inclusive observation window.
        val firstWindowTalk = onset?.let { start ->
            stages.indices.firstOrNull {
                stages[it] == Stage.TALK && START.plusMonths(it.toLong()) in start..start.plusMonths(12)
            }?.let { START.plusMonths(it.toLong()) }
        }
        val detected = firstWindowTalk != null
        val entries = if (onset != null) 0 else stages.indices.count {
            stages[it] == Stage.TALK && (it == 0 || stages[it - 1] != Stage.TALK)
        }
        return PersonaOutcome(id, onset, firstTalk, detected, entries, monthly.size / 12.0,
            if (detected) ChronoUnit.MONTHS.between(firstWindowTalk, onset!!.plusMonths(12)).toDouble() else null,
            onset != null && end < onset.plusMonths(12), stages, monthly)
    }
}
