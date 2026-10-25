package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.TripFeatures
import java.time.Instant
import java.time.LocalDate

data class TodayLineUi(val title: String, val body: String, val center: Boolean = false)

const val TODAY_LINE_NOTICE = "보건복지부 치매예방수칙 3·3·3을 챙기도록 도와드려요. 핸들온은 질병을 진단하거나 예방하지 않아요."

private val todayLines = listOf(
    TodayLineUi("가까운 곳을 10분 걸어 볼까요?", "일주일에 세 번 이상 걷기는 3·3·3 수칙의 첫 번째예요."),
    TodayLineUi("생선과 채소를 골고루 드셨나요?", "골고루 먹기도 3·3·3 수칙에 들어 있어요."),
    TodayLineUi("책이나 신문을 읽고 짧게 적어 보세요", "읽고 쓰기는 3·3·3 수칙의 하나예요."),
    TodayLineUi("가족이나 친구에게 안부 전화 한 통 어때요?", "자주 연락하며 지내기도 수칙에 있어요."),
    TodayLineUi("혈압·혈당·콜레스테롤 검사는 언제 하셨나요?", "정기 건강검진을 챙겨 보세요."),
    TodayLineUi("청력과 시력도 정기적으로 점검해 보세요", "잘 듣고 잘 보는 것도 꾸준히 챙겨요."),
    TodayLineUi("만 60세 이상이면 치매안심센터에서 무료 선별검사를 받을 수 있어요", "가까운 곳과 길안내를 바로 볼 수 있어요.", center = true),
)

fun todayLine(day: LocalDate, mode: Mode): TodayLineUi {
    val size = if (mode == Mode.MAEUM) todayLines.size else todayLines.size - 1
    return todayLines[Math.floorMod(day.toEpochDay(), size.toLong()).toInt()]
}

fun hasTripToday(trips: List<TripFeatures>, day: LocalDate): Boolean =
    trips.any { Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() == day }

fun visibleTodayLine(day: LocalDate, mode: Mode, hasTrip: Boolean, dismissedDay: String?): TodayLineUi? =
    todayLine(day, mode).takeIf { hasTrip && dismissedDay != day.toString() }
