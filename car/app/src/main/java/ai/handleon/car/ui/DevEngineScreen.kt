package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.Signal
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 신호 구독·계산·이동 상태를 갖지 않는 시연 화면. */
@Composable
fun DevEngineScreen(ui: DevEngineUi, onBack: () -> Unit, gleoPanel: (@Composable () -> Unit)? = null) {
    ScreenFrame(topBar = {
        GlassPanel(Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            drawRoundRect(DarkTokens.threshold.copy(alpha = .18f), cornerRadius = CornerRadius(28.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
        }, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ai.handleon.car.ui.components.IconButton(MidnightIcons.Back, "뒤로", onBack)
                Column(Modifier.weight(1f)) {
                    Text("발표 모드 엔진 화면", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink)
                    Text("발표 모드 · 시연용 화면", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
                    ui.waitingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
                }
                IconTile(EngineIcons.Monitor, DarkTokens.threshold)
            }
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 1300.dp
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
                SignalPanel(ui, compact, Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.align(Alignment.CenterVertically).size(if (compact) 40.dp else 56.dp)
                    .background(Color.White.copy(alpha = .06f), CircleShape)
                    .border(1.dp, DarkTokens.line, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(EngineIcons.Forward, null, Modifier.size(28.dp), tint = DarkTokens.brand)
                }
                EnginePanel(ui, compact, Modifier.weight(1f).fillMaxHeight())
            }
        }
        gleoPanel?.invoke()
    }
}

@Composable
private fun SignalPanel(ui: DevEngineUi, compact: Boolean, modifier: Modifier) {
    val gap = if (compact) 10.dp else 14.dp
    GlassPanel(modifier, padding = if (compact) 20.dp else 32.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StageDot(DarkTokens.brand)
            Text("Pleos 신호 · 지금", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            GlassPanel(Modifier.weight(2f).fillMaxHeight(), radius = 20.dp, padding = if (compact) 16.dp else 24.dp) {
                SignalLabel("속도")
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.speedText, style = MaterialTheme.typography.displayLarge.copy(fontSize = if (compact) 64.sp else 104.sp,
                        lineHeight = if (compact) 76.sp else 112.sp), color = DarkTokens.ink)
                    Text("km/h", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
                }
                if (ui.speedText == "—") MissingSignal()
            }
            GlassPanel(Modifier.weight(1f).fillMaxHeight(), radius = 20.dp, padding = if (compact) 12.dp else 20.dp) {
                SignalLabel("제한속도")
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(if (compact) 64.dp else 88.dp).border(4.dp, DarkTokens.threshold, CircleShape), Alignment.Center) {
                    Text(ui.speedLimitText, style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink, fontWeight = FontWeight.W800)
                }
                Text("km/h", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
                if (ui.speedLimitText == "—") MissingSignal("내비 정보가 없어요")
            }
        }
        Spacer(Modifier.height(gap))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            SignalTile("기어", ui.gearText, compact, Modifier.weight(1f).fillMaxHeight(), DarkTokens.brand)
            SignalTile("조향", ui.steeringText, compact, Modifier.weight(1f).fillMaxHeight())
            SignalTile("방향지시등", ui.signalText, compact, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(gap))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            SignalTile("와이퍼", ui.wiperText, compact, Modifier.weight(1f).fillMaxHeight())
            SignalTile("브레이크 페달", ui.brakeText, compact, Modifier.weight(1f).fillMaxHeight())
            SignalTile("경고등", ui.telltalesText, compact, Modifier.weight(1f).fillMaxHeight(), longValue = ui.telltalesText.length > 4)
        }
        Spacer(Modifier.height(gap))
        GlassPanel(Modifier.fillMaxWidth(), radius = 20.dp, padding = if (compact) 16.dp else 24.dp) {
            SignalLabel("안내 상태 · 내비 다음 회전")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ui.guidanceText, Modifier.weight(1f), style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineMedium,
                    color = DarkTokens.ink, fontWeight = FontWeight.W800)
                ui.turnDirection?.let {
                    Icon(MidnightIcons.Turn, null, Modifier.size(if (compact) 40.dp else 64.dp)
                        .graphicsLayer { scaleX = if (it == Signal.RIGHT) -1f else 1f }, tint = DarkTokens.domainWayfinding)
                }
            }
            if (ui.guidanceText == "—") MissingSignal("내비 신호를 기다려요")
        }
    }
}

@Composable
private fun SignalLabel(label: String) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
}

@Composable
private fun MissingSignal(text: String = "신호를 기다려요") {
    Text(text, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
}

@Composable
private fun SignalTile(label: String, value: String, compact: Boolean, modifier: Modifier,
                       color: Color = DarkTokens.ink, longValue: Boolean = false) {
    GlassPanel(modifier.heightIn(min = if (compact) 112.dp else 138.dp), radius = 20.dp, padding = if (compact) 12.dp else 20.dp) {
        SignalLabel(label)
        Spacer(Modifier.height(8.dp))
        Text(value, style = when { longValue -> MaterialTheme.typography.bodySmall
            compact -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.headlineLarge }, color = color, fontWeight = FontWeight.W800)
        if (value == "—") MissingSignal()
    }
}

@Composable
private fun EnginePanel(ui: DevEngineUi, compact: Boolean, modifier: Modifier) {
    GlassPanel(modifier, padding = if (compact) 20.dp else 32.dp) {
        Text("이번 트립 · 엔진 지표", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink)
        ui.tripHint?.let {
            Text(it, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
        EngineMetric("주행 거리", ui.distanceText, "km", ui.distanceHistory, compact)
        EngineMetric("급제동", ui.hardBrakesText, "회", ui.hardBrakesHistory, compact)
        EngineMetric("곧은 길 핸들 흔들림", ui.steeringStdText, "°", ui.steeringHistory, compact, hint = ui.steeringHint)
        EngineMetric("예고된 회전", ui.announcedTurnsText, "회", ui.turnsHistory, compact)
        EngineMetric("방향지시등 누락", ui.signalMissesText, "회", ui.missesHistory, compact,
            color = if (ui.hasSignalMisses) DarkTokens.watchInk else DarkTokens.ink, divider = false)
        Spacer(Modifier.weight(1f).heightIn(min = 20.dp))
        val statusColor = when { ui.waitingText != null -> DarkTokens.inkMuted; ui.held -> DarkTokens.watchFill; else -> DarkTokens.brand }
        GlassPanel(Modifier.fillMaxWidth().background(statusColor.copy(alpha = .08f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .border(1.dp, statusColor.copy(alpha = .2f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)), radius = 20.dp,
            padding = if (compact) 16.dp else 24.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StageDot(statusColor)
                Text(ui.judgmentText, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = if (ui.held) DarkTokens.watchInk else DarkTokens.ink)
            }
        }
        Text("밤이거나 와이퍼가 켜지면 \"판단 보류\"로 바뀌어요.", Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
    }
}

@Composable
private fun EngineMetric(label: String, value: String, unit: String, history: List<Double?>, compact: Boolean,
                         hint: String? = null, color: Color = DarkTokens.ink, divider: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 16.dp else 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp)) {
            Text(label, Modifier.weight(1f), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = DarkTokens.threshold)
            MetricHistory(history, if (color == DarkTokens.watchInk) DarkTokens.watchFill else DarkTokens.brand, compact)
            Row(Modifier.widthIn(min = if (compact) 78.dp else 130.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                Text(value, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.W800, color = color)
                if (value != "—") Text(if (unit == "km") " km" else unit, Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = if (color == DarkTokens.watchInk) color else DarkTokens.inkMuted)
            }
        }
        hint?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
    }
    if (divider) HorizontalDivider(color = DarkTokens.line)
}

/** 2초마다 실제로 계산한 최근 지표. 값이 없는 구간에는 막대를 그리지 않는다. */
@Composable
private fun MetricHistory(values: List<Double?>, color: Color, compact: Boolean) {
    Canvas(Modifier.size(width = if (compact) 58.dp else 144.dp, height = 44.dp)
        .semantics { contentDescription = "최근 지표 변화 · 2초 간격" }) {
        val maximum = values.filterNotNull().filter(Double::isFinite).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val step = size.width / 10
        values.takeLast(10).forEachIndexed { index, value ->
            if (value != null && value.isFinite()) {
                val height = (value / maximum).toFloat().coerceIn(0f, 1f) * (size.height - 6.dp.toPx()) + 6.dp.toPx()
                drawRoundRect(if (index == values.lastIndex) color else DarkTokens.threshold.copy(alpha = .2f),
                    topLeft = Offset(index * step, size.height - height), size = Size(step * .6f, height), cornerRadius = CornerRadius(step * .3f))
            }
        }
    }
}

private object EngineIcons {
    private fun icon(name: String, paths: String) = ImageVector.Builder(name, 32.dp, 32.dp, 24f, 24f)
        .addPath(addPathNodes(paths), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val Monitor = icon("EngineMonitor", "M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M12 16v4 M8 20h8 M7 12l3-3 2 2 4-4")
    val Forward = icon("EngineForward", "M5 12h14 M13 6l6 6-6 6")
}
