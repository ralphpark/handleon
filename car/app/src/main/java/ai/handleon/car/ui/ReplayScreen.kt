package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.car.replay.ReplayPersona
import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.Stage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.LocalDate

data class ReplayUi(
    val mode: Mode = Mode.MAEUM, val maeum: MaeumUi = MaeumUi.from(null, null),
    val condition: ConditionUi = ConditionUi.from(null), val stages: List<Stage> = emptyList(),
    val start: YearMonth? = null, val frame: Int = 0, val total: Int = 24,
    val paused: Boolean = false, val complete: Boolean = false, val error: String? = null,
    val adapt: List<ai.handleon.driveprint.AdaptWeek> = emptyList(),
    val persona: ReplayPersona = ReplayPersona.FATHER,
    val choices: List<ReplayPersona> = ReplayPersona.forMode(Mode.MAEUM),
    val displayDay: LocalDate? = null, val firstDrive: Boolean = false,
    val weekly: ai.handleon.driveprint.WeeklyReport? = null, val tripLine: String? = null,
    val habits: ai.handleon.driveprint.HabitSummary? = null, val uploadMessage: String? = null,
)

@Composable
fun ReplayScreen(ui: ReplayUi, onPause: () -> Unit, onEnd: () -> Unit, onCenter: () -> Unit = {},
                 onPersona: (ReplayPersona) -> Unit = {}, forecastContent: @Composable () -> Unit = {}) {
    val talk = ui.mode == Mode.MAEUM && ui.maeum.showCenter
    ScreenFrame(talk = talk, notice = true, footer = { wide -> if (talk) CenterBanner(onCenter, wide) }, topBar = { wide ->
        val banner = Modifier.fillMaxWidth().background(DarkTokens.replaySoft, RoundedCornerShape(28.dp))
            .border(1.dp, DarkTokens.replay.copy(alpha = .25f), RoundedCornerShape(28.dp)).padding(14.dp)
        if (wide) Row(banner, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).padding(start = 14.dp)) { ReplayBannerText(ui) }
            ReplayActions(ui, onPause, onEnd)
        } else Column(banner, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ReplayBannerText(ui)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { ReplayActions(ui, onPause, onEnd) }
        }
    }) { wide ->
        Text("재생할 인물 고르기 · 모의", style = MaterialTheme.typography.titleSmall, color = DarkTokens.replay)
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.choices.forEach { persona -> PersonaButton(persona, ui.persona, onPersona) }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.choices.forEach { persona -> PersonaButton(persona, ui.persona, onPersona) }
        }
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }
        ui.uploadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.replay) }
        if (ui.mode == Mode.MAEUM) {
            val month = ui.start?.takeIf { ui.frame > 0 }?.plusMonths(ui.frame.toLong() - 1)
            val title: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when {
                        ui.frame == 0 -> "${ui.persona.label} · 재생 준비 중"
                        ui.firstDrive -> "새 차 · 첫 운전"
                        ui.persona == ReplayPersona.FATHER -> "아버지 · 그랜저 · ${ui.total}개월 중 ${ui.frame}번째 달"
                        else -> "${ui.persona.label} · ${ui.total}장면 중 ${ui.frame}번째"
                    },
                        style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
                    Text(if (ui.persona == ReplayPersona.FATHER) month?.let { "${it.year}년 ${it.monthValue}월" } ?: "운전 기록을 열고 있어요"
                        else ui.displayDay?.let { "${it.year}년 ${it.monthValue}월 ${it.dayOfMonth}일" } ?: "운전 기록을 열고 있어요",
                        style = MaterialTheme.typography.displayLarge, color = DarkTokens.ink)
                }
            }
            val stage: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StageDot(stageColor(ui.maeum.stage))
                    Text(ui.maeum.title, style = MaterialTheme.typography.titleMedium, color = stageColor(ui.maeum.stage))
                }
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { title() }; Box(Modifier.weight(1f)) { stage() }
            } else { title(); stage() }
            ui.tripLine?.let { line -> GlassPanel(Modifier.fillMaxWidth()) {
                Text(if (ui.firstDrive) "첫 운전 뒤 한 줄 · 모의" else "최근 운전 한 줄 · 모의", style = MaterialTheme.typography.titleSmall, color = DarkTokens.replay)
                Text(line, style = MaterialTheme.typography.bodyLarge, color = DarkTokens.ink)
                ui.habits?.let { h -> Text("${h.from.monthValue}/${h.from.dayOfMonth} 주 · ${h.trips}번 운전 · ${h.minutes}분", color = DarkTokens.inkMuted) }
            } }
            ui.maeum.readiness?.let { Text(it, color = DarkTokens.brand) }
            ui.maeum.weekly?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = DarkTokens.ink) }
            // C6 can insert its actual forecast band here without replacing the persona flow or charts.
            forecastContent()
            if (ui.persona == ReplayPersona.FATHER) RoadTimeline(ui.stages, ui.start, ui.total)
            if (ui.persona == ReplayPersona.NEW_CAR) GlassPanel(Modifier.fillMaxWidth()) {
                Text("새 차 적응 · 모의", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
                Text(ADAPT_DESCRIPTION, color = DarkTokens.inkMuted)
                Text(ADAPT_NOTICE, color = DarkTokens.replay)
                if (ui.adapt.isEmpty()) Text("발표 모드에서 모의 적응 기록을 볼 수 있어요", color = DarkTokens.inkMuted)
                else AdaptationChart(ui.adapt)
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                GlassPanel(Modifier.weight(1f), padding = 32.dp) { ReplayReasons(ui.maeum) }
                GlassPanel(Modifier.weight(1f), padding = 32.dp) { TrendChart(ui.maeum.months, ui.maeum.thresholds, ui.maeum.baselineWindows) }
            } else {
                GlassPanel(Modifier.fillMaxWidth(), padding = 32.dp) { ReplayReasons(ui.maeum) }
                GlassPanel(Modifier.fillMaxWidth(), padding = 32.dp) { TrendChart(ui.maeum.months, ui.maeum.thresholds, ui.maeum.baselineWindows) }
            }
            ui.maeum.homeHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
        } else {
            Text(if (ui.frame == 0) "자녀 · 재생 준비 중" else "자녀 · ${ui.total}운전일 중 ${ui.frame}번째 날",
                style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
            ConditionContent(ui.condition, wide, onLabels = {}, labelsEnabled = false)
        }
    }
}

@Composable
private fun ReplayReasons(ui: MaeumUi) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("이번 달 달라진 점", style = MaterialTheme.typography.titleLarge, color = DarkTokens.ink)
        ui.reasons.forEach { ReasonRow(it) }
        ui.collectionText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.brand) }
        ui.onset?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }
        Text("한 영역만 달라진 건 지켜봐요. 두 영역 이상이 달라지고 그중 하나가 지난달에도 그랬다면 대화를 권해 드려요.",
            style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted)
    }
}

@Composable
private fun ReplayBannerText(ui: ReplayUi) {
    Text("재생 · 모의 · 가상 인물 데이터", style = MaterialTheme.typography.titleSmall, color = DarkTokens.replay)
    Text(if (ui.complete) "마지막 기록까지 보셨어요" else "실제 기록이 아니에요. 엔진은 실제 엔진 그대로예요.",
        style = MaterialTheme.typography.bodySmall, color = DarkTokens.replay)
}

@Composable
private fun PersonaButton(persona: ReplayPersona, selected: ReplayPersona, onPersona: (ReplayPersona) -> Unit) {
    PillButton(if (persona == selected) "${persona.label} · 다시 보기" else persona.label,
        { onPersona(persona) }, kind = if (persona == selected) PillKind.REPLAY else PillKind.SECONDARY)
}

@Composable
private fun ReplayActions(ui: ReplayUi, onPause: () -> Unit, onEnd: () -> Unit) {
    PillButton(if (ui.paused) "계속 재생" else "일시 정지", onPause, kind = PillKind.SECONDARY, enabled = !ui.complete && ui.error == null)
    PillButton("재생 끝내기", onEnd, kind = PillKind.REPLAY)
}
