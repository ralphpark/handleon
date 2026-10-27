package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
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

data class ReplayUi(
    val mode: Mode = Mode.MAEUM, val maeum: MaeumUi = MaeumUi.from(null, null),
    val condition: ConditionUi = ConditionUi.from(null), val stages: List<Stage> = emptyList(),
    val start: YearMonth? = null, val frame: Int = 0, val total: Int = 24,
    val paused: Boolean = false, val complete: Boolean = false, val error: String? = null,
    val adapt: List<ai.handleon.driveprint.AdaptWeek> = emptyList(),
)

@Composable
fun ReplayScreen(ui: ReplayUi, onPause: () -> Unit, onEnd: () -> Unit, onCenter: () -> Unit = {}) {
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
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }
        if (ui.mode == Mode.MAEUM) {
            val month = ui.start?.takeIf { ui.frame > 0 }?.plusMonths(ui.frame.toLong() - 1)
            val title: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (ui.frame == 0) "아버지 · 그랜저 · 재생 준비 중" else "아버지 · 그랜저 · ${ui.total}개월 중 ${ui.frame}번째 달",
                        style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
                    Text(month?.let { "${it.year}년 ${it.monthValue}월" } ?: "운전 기록을 열고 있어요",
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
            RoadTimeline(ui.stages, ui.start, ui.total)
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
    Text("재생 중 · 가상 인물 데이터", style = MaterialTheme.typography.titleSmall, color = DarkTokens.replay)
    Text(if (ui.complete) "마지막 기록까지 보셨어요" else "실제 기록이 아니에요. 엔진은 실제 엔진 그대로예요.",
        style = MaterialTheme.typography.bodySmall, color = DarkTokens.replay)
}

@Composable
private fun ReplayActions(ui: ReplayUi, onPause: () -> Unit, onEnd: () -> Unit) {
    PillButton(if (ui.paused) "계속 재생" else "일시 정지", onPause, kind = PillKind.SECONDARY, enabled = !ui.complete && ui.error == null)
    PillButton("재생 끝내기", onEnd, kind = PillKind.REPLAY)
}
