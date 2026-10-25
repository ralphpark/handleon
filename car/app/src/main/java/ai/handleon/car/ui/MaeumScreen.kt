package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.car.warmstart.BlueLinkMock
import ai.handleon.driveprint.Stage
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MaeumScreen(ui: MaeumUi, onLabels: () -> Unit = {}, onSettings: () -> Unit = {},
                onCenter: () -> Unit = {}, onTab: (Boolean) -> Unit = {}, onLetter: () -> Unit = {},
                headerExtra: @Composable RowScope.() -> Unit = {}, homeCard: @Composable () -> Unit = {},
                compactHome: Boolean = false) {
    ScreenFrame(talk = ui.showCenter, notice = true, compact = compactHome, footer = { wide ->
        if (ui.showCenter && !ui.pastSelected) {
            if (wide) CenterBanner(onCenter, true)
            else PillButton("가까운 곳 보기", onCenter, Modifier.fillMaxWidth(), PillKind.CORAL, icon = MidnightIcons.Next)
        }
    }) { wide ->
        HomeHeader("마음 운전", ui.period, onLabels, onSettings) {
            headerExtra()
            if (wide) SegmentedControl(if (ui.pastSelected) 1 else 0, { onTab(it == 1) })
        }
        homeCard()
        if (!wide) SegmentedControl(if (ui.pastSelected) 1 else 0, { onTab(it == 1) })
        if (ui.pastSelected) {
            GlassPanel(Modifier.fillMaxWidth(), padding = 32.dp) { TrendChart(ui.months, ui.thresholds, ui.baselineWindows) }
            ReplayNotice(BlueLinkMock.NOTICE)
            GlassPanel(Modifier.fillMaxWidth(), padding = 32.dp) { DistanceChart(ui.blueLink) }
        } else if (compactHome && wide) CompactMaeumContent(ui, onLetter)
        else MaeumContent(ui, wide, onLetter)
    }
}

/** A short parked card leaves the center footer and the current report visible at 1920 × 720. */
@Composable
private fun CompactMaeumContent(ui: MaeumUi, onLetter: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassPanel(Modifier.weight(1f), padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RingGauge(ui.rings, compact = true, diameter = 180.dp)
                Column(Modifier.weight(1f)) {
                    HeroCopy(ui, true)
                    ui.collectionText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.brand) }
                    ui.homeHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
                }
            }
        }
        GlassPanel(Modifier.weight(1f), padding = 20.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("평소와 달라진 점", style = MaterialTheme.typography.titleSmall, color = DarkTokens.threshold)
                ui.visibleLetter?.let { PillButton("이번 달 편지 보기", onLetter, kind = PillKind.SECONDARY) }
            }
            if (ui.reasons.isEmpty()) Text("새로 알려 드릴 변화가 없어요.", style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted)
            ui.reasons.forEach {
                Text(it.line, style = MaterialTheme.typography.bodySmall, color = DarkTokens.ink)
                Text(it.evidence, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
        }
    }
}

@Composable
fun MaeumContent(ui: MaeumUi, wide: Boolean, onLetter: () -> Unit = {}) {
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        MaeumHero(ui, Modifier.weight(1f), compact = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MaeumReasons(ui)
            ui.visibleLetter?.let { LetterCard(it, onLetter) }
        }
    } else {
        MaeumHero(ui, Modifier.fillMaxWidth(), compact = true)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { MaeumReasons(ui, compact = true) }
        ui.visibleLetter?.let { LetterCard(it, onLetter) }
    }
    ui.homeHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
}

@Composable
internal fun MaeumReasons(ui: MaeumUi, compact: Boolean = false) {
    if (!compact) Text("평소와 달라진 점", style = MaterialTheme.typography.titleMedium, color = DarkTokens.threshold)
    ui.reasons.forEach { reason -> GlassPanel(Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = if (compact) 26.dp else 28.dp, vertical = if (compact) 22.dp else 24.dp)) { ReasonRow(reason) } }
    if (ui.reasons.isEmpty()) GlassPanel(Modifier.fillMaxWidth()) {
        Text(if (ui.stage == Stage.BUILDING) "운전하실 때마다 나만의 기준이 차곡차곡 쌓여요." else "새로 알려 드릴 변화가 없어요.",
            style = MaterialTheme.typography.bodyLarge, color = DarkTokens.inkMuted)
    }
}

@Composable
private fun MaeumHero(ui: MaeumUi, modifier: Modifier, compact: Boolean) {
    GlassPanel(modifier, radius = 36.dp, padding = if (compact) 36.dp else 44.dp) {
        if (compact) {
            BoxWithConstraints {
                if (maxWidth >= 580.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    RingGauge(ui.rings, compact = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { HeroCopy(ui, true) }
                } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    HeroCopy(ui, true)
                    RingGauge(ui.rings, Modifier.align(Alignment.CenterHorizontally), compact = true)
                }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(36.dp)) {
            HeroCopy(ui, false)
            BoxWithConstraints {
                if (maxWidth >= 610.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    RingGauge(ui.rings)
                    RingLegend(ui, Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    RingGauge(ui.rings, Modifier.align(Alignment.CenterHorizontally))
                    RingLegend(ui)
                }
            }
            ui.onset?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }
        }
        ui.collectionText?.let { Spacer(Modifier.height(24.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.brand) }
    }
}

@Composable
private fun HeroCopy(ui: MaeumUi, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StageDot(stageColor(ui.stage))
        Text(when (ui.stage) { Stage.BUILDING -> "적립 중"; Stage.STABLE -> "안정"; Stage.WATCH -> "관찰"; Stage.TALK -> "대화 권유" },
            style = MaterialTheme.typography.labelLarge, color = stageColor(ui.stage))
    }
    Text(ui.title, style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge, color = DarkTokens.ink)
    if (compact) ui.onset?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }
}

@Composable
private fun RingLegend(ui: MaeumUi, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ui.rings.forEach { ring ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StageDot(if (ring.over) domainColor(ring.domain) else DarkTokens.hold)
                    Text(domainName(ring.domain), style = MaterialTheme.typography.titleSmall, color = DarkTokens.ink)
                }
                Text(when { ring.fraction == null -> "기록을 모으는 중"; ring.over -> "평소 범위를 넘었어요"; else -> "평소와 비슷해요" },
                    style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
        }
        Text("점선 · 평소 범위", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
    }
}
