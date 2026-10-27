package ai.handleon.car.ui

import ai.handleon.driveprint.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** C8 supplies adaptation projections later; C3 only reserves their place in the résumé. */
data class AdaptWeek(val weekStart: LocalDate, val line: String)
data class ResumeUi(val week: HabitSummary, val month: HabitSummary, val promises: List<PromiseResult>,
                    val adapt: List<AdaptWeek> = emptyList(), val blueLink: String? = null)

fun promiseTitle(p: Promise): String = when (p) {
    Promise.NO_NIGHT -> "해 지면 운전 안 하기"
    Promise.REST_2H -> "2시간마다 쉬기"
    Promise.NO_RAIN -> "비 오는 날은 쉬기"
}
fun carTripsThrough(trips: List<TripFeatures>, nowMs: Long) = trips.filter { it.source == Source.CAR && it.startMs <= nowMs && it.endMs <= nowMs }
fun buildResume(trips: List<TripFeatures>, selected: Set<Promise>, now: ZonedDateTime): ResumeUi {
    val today = now.withZoneSameInstant(KST).toLocalDate()
    val monday = today.minusDays(today.dayOfWeek.value - 1L)
    val car = carTripsThrough(trips, now.toInstant().toEpochMilli())
    val outings = Outings.group(car, now.toInstant().toEpochMilli())
    val thisWeek = car.filter { Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() >= monday }
    val weekOutings = outings.filter { Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() >= monday }
    return ResumeUi(Habits.summary(car, outings, monday, monday.plusWeeks(1), KST),
        Habits.summary(car, outings, today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1), KST),
        Promises.evaluate(selected, thisWeek, weekOutings))
}
fun resumeLines(ui: ResumeUi): List<String> = buildList {
    fun section(title: String, h: HabitSummary) {
        add(title); add("${h.trips}번 운전 · ${h.minutes}분")
        add(if (h.signalTotal == 0) "방향지시등을 살펴볼 기록이 아직 없어요" else "방향지시등 ${h.signalTotal}번 중 ${h.signalKept}번 켰어요")
        add("급제동·급가속 없이 운전 ${h.calmTrips}번")
        add("2시간 넘게 이어 운전 ${h.longStretches}번")
    }
    section("이번 주", ui.week); section("이번 달", ui.month)
    add("운전 약속")
    if (ui.promises.isEmpty()) add("지키고 싶은 운전 약속을 골라 보세요")
    ui.promises.sortedBy { it.promise.ordinal }.forEach {
        add("${promiseTitle(it.promise)} · 지킨 기록 ${it.kept} / ${it.kept + it.broken}번")
    }
    add("새 차 적응")
    if (ui.adapt.isEmpty()) add("새 차 적응 기록은 준비 중이에요") else ui.adapt.forEach { add(it.line) }
    add("이전 차와 비교")
    add(ui.blueLink ?: "이전 차와 비교할 기록은 준비 중이에요")
}
fun sunsetLine(selected: Set<Promise>, home: LatLon?, today: LocalDate): String? {
    if (Promise.NO_NIGHT !in selected) return null
    val sunset = runCatching { Instant.ofEpochMilli(Sun.sunsetMs(home ?: LatLon(37.5663, 126.9779), today)).atZone(KST) }.getOrNull() ?: return null
    val hour = (sunset.hour % 12).let { if (it == 0) 12 else it }
    return "오늘 해 지는 시각 ${hour}시 ${sunset.minute}분"
}
