package ai.handleon.sim

import ai.handleon.driveprint.KoreanHolidays
import ai.handleon.driveprint.TripFeatures
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
internal data class Replay(val version: Int, val persona: String, val trips: List<TripFeatures>)

object Personas {
    private val zone = ZoneId.of("Asia/Seoul")

    // 규칙 D(엔진 기본, 2026-10-23) 아래에서 첫 TALK 가 2026-09 이고 10월도 TALK 인 첫 시드.
    // (규칙 A 때는 21. 01b 전의 11 은 OPERATION 인위 경로로 TALK 였다.) 탐색: :sim:run --args="--search-father-seeds 60"
    const val FATHER_SEED = 17L
    fun father(seed: Long = FATHER_SEED): List<TripFeatures> = Generator.trips(
        Persona(0, YearMonth.of(2025, 11), 60.0, 12, 30.0, seed), YearMonth.of(2024, 11), 24)

    fun sudden(seed: Long = 5): List<TripFeatures> = Generator.trips(
        Persona(5, null, 60.0, 12, 30.0, seed, suddenDrop = LocalDate.of(2026, 6, 1)),
        YearMonth.of(2025, 11), 12)

    fun child(): List<TripFeatures> {
        val start = LocalDate.of(2026, 8, 24)
        val templates = Generator.trips(Persona(1, null, 60.0, 12, 30.0, 9L), YearMonth.of(2026, 8), 3)
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) }
        val used = mutableMapOf<YearMonth, Int>()
        // The replay prescribes two commutes every day. Reuse each month's generated
        // trip measurements and weather, cycling only if its Poisson count is below demand.
        return (0 until 60).flatMap { dayIndex ->
            val day = start.plusDays(dayIndex.toLong())
            val month = YearMonth.from(day)
            val pool = templates.getValue(month)
            List(2) { leg ->
                val index = used.getOrDefault(month, 0)
                used[month] = index + 1
                val trip = pool[index % pool.size]
                val hour = if (leg == 0) { if (trip.night) 5 else 8 } else { if (trip.night) 21 else 16 }
                val startMs = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                trip.copy(startMs = startMs, endMs = startMs + trip.endMs - trip.startMs,
                    offDay = KoreanHolidays.isOffDay(day),
                    steeringStd = trip.steeringStd?.times(if (dayIndex >= 58) 2.0 else 1.0))
            }
        }
    }
}
