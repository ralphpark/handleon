package ai.handleon.car.ui

import ai.handleon.car.collect.DriveSessionView

const val REST_BODY = "졸음이 느껴지면 멈춰서 쉬는 것이 가장 안전해요. 커피 한 잔을 마시고 15분 안쪽으로 짧게 눈을 붙여 보세요."
const val REST_FINISHED = "15분이 지났어요. 몸을 천천히 풀고 출발해요"
const val REST_DRIVING = "오래 운전했어요. 다음 휴게소에서 잠깐 쉬어 가요"
const val REST_TIMER_MS = 15 * 60_000L

data class RestUi(val remainingMs: Long? = null, val finished: Boolean = false) {
    val title get() = if (remainingMs != null) {
        val seconds = ((remainingMs.coerceAtLeast(0) + 999) / 1000)
        "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    } else "잠깐 쉬어 가요"
    val body get() = if (finished) REST_FINISHED else REST_BODY
}

sealed interface HomeCard {
    data class Rest(val ui: RestUi) : HomeCard
    data class DriverCheck(val startMs: Long, val minutes: Int) : HomeCard
    data class Confirm(val action: PendingConfirm) : HomeCard
    data class LostWay(val tripStartMs: Long) : HomeCard
    data class Trip(val line: String) : HomeCard
    data class Week(val story: String) : HomeCard
    data class Note(val ui: CarNote) : HomeCard
    data class Line(val ui: TodayLineUi) : HomeCard
}

sealed interface PendingConfirm {
    data object MarkOtherDriver : PendingConfirm
    data class MarkOtherTrip(val startMs: Long, val requestedAtMs: Long) : PendingConfirm
    data object PauseSharing : PendingConfirm
}

data class HomeInputs(val rest: RestUi? = null, val driverCheck: HomeCard.DriverCheck? = null,
                      val confirm: PendingConfirm? = null, val lostWay: Long? = null, val note: CarNote? = null,
                      val trip: HomeCard.Trip? = null, val week: String? = null, val line: TodayLineUi? = null)
fun topCard(inputs: HomeInputs): HomeCard? = with(inputs) { when {
    rest != null -> HomeCard.Rest(rest)
    driverCheck != null -> driverCheck
    confirm != null -> HomeCard.Confirm(confirm)
    lostWay != null -> HomeCard.LostWay(lostWay)
    note != null -> HomeCard.Note(note)
    trip != null -> trip
    week != null -> HomeCard.Week(week)
    line != null -> HomeCard.Line(line)
    else -> null
} }
fun topCard(rest: RestUi?, note: CarNote?, line: TodayLineUi?): HomeCard? =
    topCard(HomeInputs(rest = rest, note = note, line = line))

fun sharingBanner(recipientsWithDriveDone: Int): String? = ai.handleon.car.sync.DriveNotices.sharingLine(recipientsWithDriveDone)
fun weekCardKey(now: java.time.ZonedDateTime): String {
    val day = now.withZoneSameInstant(KST).toLocalDate()
    val monday = day.minusDays(day.dayOfWeek.value - 1L)
    return (if (day.dayOfWeek == java.time.DayOfWeek.MONDAY) monday.minusWeeks(1) else monday).toString()
}
fun weekCardVisible(now: java.time.ZonedDateTime, dismissedWeek: String?): Boolean {
    val local = now.withZoneSameInstant(KST)
    return dismissedWeek != weekCardKey(local) && (local.dayOfWeek == java.time.DayOfWeek.MONDAY ||
        (local.dayOfWeek == java.time.DayOfWeek.SUNDAY && local.hour >= 18))
}
suspend fun runConfirmed(action: PendingConfirm?, markOther: suspend () -> Unit, pauseSharing: suspend () -> Unit,
                         consume: () -> Unit = {}) {
    if (action == null) return
    // A network failure after a local mutation must not leave a live confirmation targeting the next trip.
    consume()
    when (action) {
        PendingConfirm.MarkOtherDriver, is PendingConfirm.MarkOtherTrip -> markOther()
        PendingConfirm.PauseSharing -> pauseSharing()
        null -> Unit
    }
}

/** [dismissedStartMs]: "괜찮아요"를 누른 운전의 시작 시각. 그 운전에서는 다시 띄우지 않는다. */
fun restEligible(session: DriveSessionView?, nowMs: Long, restAfterMs: Long, dismissedStartMs: Long? = null): Boolean =
    session != null && session.startMs != dismissedStartMs &&
        session.continuousMs >= restAfterMs && nowMs - session.lastMovingMs in 0 until 30 * 60_000L

fun continuousDriveText(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0) / 60_000
    return "연속 운전 " + if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
}

data class RestTimerTick(val endMs: Long?, val finished: Boolean = false, val notify: Boolean = false)

/** Driving always wins, including the exact tick at which the timer would finish. */
fun restTimerTick(endMs: Long?, nowMs: Long, driving: Boolean, resumed: Boolean): RestTimerTick = when {
    driving -> RestTimerTick(null)
    endMs != null && nowMs >= endMs && resumed -> RestTimerTick(null, finished = true, notify = true)
    else -> RestTimerTick(endMs)
}
