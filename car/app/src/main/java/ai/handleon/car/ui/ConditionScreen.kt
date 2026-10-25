package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.ConditionStatus
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConditionScreen(ui: ConditionUi, onLabels: () -> Unit = {}, onSettings: () -> Unit = {},
                    headerExtra: @Composable RowScope.() -> Unit = {}, homeCard: @Composable () -> Unit = {},
                    compactHome: Boolean = false) {
    ScreenFrame(notice = true, compact = compactHome) { wide ->
        HomeHeader("운전 컨디션", ui.period, onLabels, onSettings, headerExtra)
        homeCard()
        if (compactHome && wide) CompactConditionContent(ui, onLabels)
        else ConditionContent(ui, wide, onLabels)
    }
}

@Composable
private fun CompactConditionContent(ui: ConditionUi, onLabels: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassPanel(Modifier.weight(1f), padding = 20.dp) {
            ConditionTitle(ui, false, compact = true)
            if (ui.reasons.isEmpty()) Text("나만의 기준이 쌓이면 평소와 견주어 알려 드릴게요.",
                style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            ui.reasons.forEach {
                Text(it.line, style = MaterialTheme.typography.bodySmall, color = DarkTokens.ink)
                Text(it.evidence, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
            Text("운전 지문 적립 · ${ui.collectedDays.coerceIn(0, 7)} / 7 운전일", style = MaterialTheme.typography.bodyMedium, color = DarkTokens.brand)
            PillButton("라벨 붙이기", onLabels, kind = PillKind.SECONDARY)
        }
        GlassPanel(Modifier.weight(1f), padding = 20.dp) {
            Text("최근 14운전일", style = MaterialTheme.typography.titleSmall, color = DarkTokens.ink)
            Text("지난 28일의 나와 견준 정도예요. 점선을 이틀 연속 넘으면 알려 드려요.", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            DayBars(ui.days, chartHeight = 140.dp)
        }
    }
}

@Composable
fun ConditionContent(ui: ConditionUi, wide: Boolean, onLabels: () -> Unit, labelsEnabled: Boolean = true) {
    GlassPanel(Modifier.fillMaxWidth(), radius = 36.dp, padding = 44.dp) {
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) { ConditionTitle(ui, wide) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) { ConditionReasons(ui) }
        } else Column(verticalArrangement = Arrangement.spacedBy(28.dp)) { ConditionTitle(ui, false); ConditionReasons(ui) }
    }
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        GlassPanel(Modifier.weight(1f), padding = 32.dp) { DaySection(ui) }
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) { CollectionSection(ui, onLabels, labelsEnabled) }
    } else {
        GlassPanel(Modifier.fillMaxWidth(), padding = 32.dp) { DaySection(ui) }
        CollectionSection(ui, onLabels, labelsEnabled)
    }
}

@Composable
private fun ConditionTitle(ui: ConditionUi, wide: Boolean, compact: Boolean = false) {
    val color = when (ui.status) { ConditionStatus.CAUTION -> DarkTokens.watchFill; ConditionStatus.OK -> DarkTokens.brand; else -> DarkTokens.hold }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StageDot(color)
        Text(when (ui.status) { ConditionStatus.CAUTION -> "이틀 연속 평소와 달라요"; ConditionStatus.OK -> "평소와 비슷해요"; ConditionStatus.BUILDING -> "운전 지문 적립 중"; ConditionStatus.HOLD -> "비교를 쉬어 가요" },
            color = color, style = MaterialTheme.typography.labelLarge)
    }
    Text(ui.title, style = if (compact) MaterialTheme.typography.titleLarge
        else if (wide) MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 80.sp)
        else MaterialTheme.typography.displaySmall, color = DarkTokens.ink)
}

@Composable
private fun ConditionReasons(ui: ConditionUi) {
    ui.reasons.forEach { ReasonRow(it) }
    if (ui.reasons.isEmpty()) Text("나만의 기준이 쌓이면 평소와 견주어 알려 드릴게요.", style = MaterialTheme.typography.bodyLarge, color = DarkTokens.inkMuted)
}

@Composable
private fun DaySection(ui: ConditionUi) {
    Text("최근 14운전일", style = MaterialTheme.typography.titleLarge, color = DarkTokens.ink)
    Spacer(Modifier.height(8.dp))
    Text("지난 28일의 나와 견준 정도예요. 점선을 이틀 연속 넘으면 알려 드려요.", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
    Spacer(Modifier.height(24.dp))
    DayBars(ui.days)
}

@Composable
private fun CollectionSection(ui: ConditionUi, onLabels: () -> Unit, labelsEnabled: Boolean) {
    GlassPanel(Modifier.fillMaxWidth(), padding = 32.dp) {
        Text("운전 지문 적립", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CollectionRing(ui.collectedDays)
            Text(if (ui.collectedDays >= 7) "최소 7운전일을 모았어요." else "운전하실 때마다 기준이 쌓여요.",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = DarkTokens.ink)
        }
        Text("평소의 내 운전을 모아 두면, 나중에 달라지는 점을 나와 견주어 볼 수 있어요.", style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
    }
    if (labelsEnabled) GlassPanel(Modifier.fillMaxWidth()) {
        Text("어젯밤 잠을 설치셨나요? 라벨을 붙여 두면 그날은 기준에서 빼 드려요.", style = MaterialTheme.typography.bodyMedium, color = DarkTokens.ink)
        Spacer(Modifier.height(16.dp))
        PillButton("라벨 붙이기", onLabels, icon = MidnightIcons.Next)
    }
}
