package ai.handleon.sim

import ai.handleon.driveprint.Domain
import ai.handleon.driveprint.MonthResult
import ai.handleon.driveprint.Stage
import java.time.YearMonth

enum class Rule { A, D, K }

/** Reclassifies fixed monthly indices; never recalibrates or changes the engine. */
object RuleComparison {
    fun stages(monthly: List<MonthResult>, start: YearMonth, months: Int, rule: Rule): List<Stage> {
        val byMonth = monthly.associateBy { it.month }
        var previous: Set<Domain>? = null
        var stage = Stage.BUILDING
        return List(months) { offset ->
            byMonth[start.plusMonths(offset.toLong())]?.let { current ->
                val over = if (rule == Rule.K) current.domains.filterValues { it > 1.2 }.keys else current.over
                val repeated = previous?.intersect(over).orEmpty()
                val talk = when (rule) {
                    Rule.A, Rule.K -> repeated.size >= 2
                    Rule.D -> over.size >= 2 && repeated.isNotEmpty()
                }
                stage = when { talk -> Stage.TALK; over.isNotEmpty() -> Stage.WATCH; else -> Stage.STABLE }
                previous = over
            }
            stage
        }
    }
}
