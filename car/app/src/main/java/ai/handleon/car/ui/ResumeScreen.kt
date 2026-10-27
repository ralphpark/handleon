package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.handleon.driveprint.AdaptWeek

@Composable
fun ResumeScreen(ui: ResumeUi, onBack: () -> Unit, onPromises: () -> Unit) {
    ScreenFrame {
        PageHeader("내 운전 이력서", onBack)
        val headings = setOf("이번 주", "이번 달", "운전 약속", "새 차 적응", "이전 차와 비교")
        for (line in resumeLines(ui)) {
            Text(line, color = DarkTokens.ink,
                style = if (line in headings) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
            if (line == ADAPT_NOTICE) AdaptationChart(ui.adapt)
        }
        PillButton("운전 약속", onPromises)
    }
}

@Composable
private fun AdaptationChart(weeks: List<AdaptWeek>) {
    val bars = adaptationBars(weeks)
    val maximum = bars.mapNotNull { it.per100km }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (bar in bars) Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bar.valueText, color = DarkTokens.ink, style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                bar.per100km?.let { value ->
                    Box(Modifier.fillMaxWidth(.65f).fillMaxHeight((value / maximum).toFloat().coerceIn(0f, 1f)).background(DarkTokens.brand))
                }
            }
            Text(bar.label, color = DarkTokens.inkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
