package ai.handleon.car.ui.components

import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.car.ui.chartRange
import ai.handleon.car.warmstart.BlueLinkMonth
import ai.handleon.driveprint.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@Composable
fun DayBars(days: List<DayResult>, modifier: Modifier = Modifier, chartHeight: Dp = 280.dp) {
    val latest = days.takeLast(14)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("점선 · 평소 범위", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        Canvas(Modifier.fillMaxWidth().height(chartHeight).semantics {
            contentDescription = "최근 ${latest.size}운전일, 기준을 넘은 날 ${latest.count { day -> day.index?.let { value -> day.threshold?.let { value > it } } == true }}일. 빈 칸은 비교할 기록이 없는 날이에요."
        }) {
            val range = chartRange(latest.flatMap { listOfNotNull(it.index, it.threshold) })
            val step = size.width / 14
            val width = step * .60f
            val bottom = size.height - 4.dp.toPx()
            fun y(value: Double) = bottom - range.fraction(value) * (bottom - 8.dp.toPx())
            val zero = y(0.0)
            repeat(14) { i ->
                val x = step * (i + .2f)
                drawRoundRect(Color(0x07FFFFFF), Offset(x, 0f), Size(width, bottom), CornerRadius(14.dp.toPx()))
                val day = latest.getOrNull(i)
                val value = day?.index
                if (value != null && value.isFinite()) {
                    val over = day.threshold?.let { value > it } == true
                    val color = if (over) DarkTokens.watchFill else DarkTokens.brand.copy(alpha = .35f)
                    val top = minOf(y(value), zero)
                    val height = kotlin.math.abs(y(value) - zero).coerceAtLeast(2.dp.toPx())
                    if (over) drawRoundRect(color.copy(alpha = .12f), Offset(x - 6.dp.toPx(), top - 6.dp.toPx()),
                        Size(width + 12.dp.toPx(), height + 6.dp.toPx()), CornerRadius(20.dp.toPx()))
                    drawRoundRect(color, Offset(x, top), Size(width, height), CornerRadius(14.dp.toPx()))
                }
                day?.threshold?.takeIf(Double::isFinite)?.let { threshold ->
                    drawLine(DarkTokens.threshold.copy(alpha = .7f), Offset(step*i, y(threshold)), Offset(step*(i+1), y(threshold)),
                        3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                }
            }
            if (range.min < 0) drawLine(DarkTokens.hold.copy(alpha = .4f), Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(latest.firstOrNull()?.day?.toString() ?: "기록을 기다리고 있어요", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            latest.lastOrNull()?.let { Text(it.day.toString(), style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
        }
    }
}

@Composable
fun TrendChart(months: List<MonthResult>, thresholds: Map<Domain, Double>,
               windows: Map<FeatureId, BaselineWindow>, modifier: Modifier = Modifier) {
    val first = (months.map { it.month } + windows.values.map { it.from }).minOrNull()
    val last = months.lastOrNull()?.month
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("영역별 변화", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        Text("옅은 띠 · 기준 기간    점선 · 각 영역의 평소 범위", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        Canvas(Modifier.fillMaxWidth().height(300.dp).semantics { contentDescription = "${months.size}개월 영역별 변화 그래프" }) {
            if (first == null || last == null) return@Canvas
            val span = ChronoUnit.MONTHS.between(first, last).coerceAtLeast(1).toFloat()
            val margin = 14.dp.toPx()
            fun x(month: YearMonth) = margin + ChronoUnit.MONTHS.between(first, month) / span * (size.width - 2 * margin)
            val ratios = months.flatMap { m -> m.domains.mapNotNull { (d,v) -> thresholds[d]?.takeIf { it > 0 }?.let { v / it } } }
            val range = chartRange(ratios + 1.0)
            fun y(value: Double) = size.height - margin - range.fraction(value) * (size.height - 2 * margin)
            windows.values.maxOfOrNull { it.toExclusive }?.let { end ->
                drawRect(Color(0x0AFFFFFF), Offset.Zero, Size(x(minOf(end, last)).coerceAtLeast(0f), size.height))
            }
            drawLine(DarkTokens.threshold.copy(alpha = .7f), Offset(0f, y(1.0)), Offset(size.width, y(1.0)), 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
            Domain.entries.forEach { domain ->
                val limit = thresholds[domain]?.takeIf { it > 0 && it.isFinite() } ?: return@forEach
                val path = Path()
                var previous: YearMonth? = null
                var end: Offset? = null
                months.forEach { month ->
                    val value = month.domains[domain]?.takeIf(Double::isFinite)
                    if (value == null) previous = null else {
                        val point = Offset(x(month.month), y(value / limit))
                        if (previous?.plusMonths(1) == month.month) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                        end = point; previous = month.month
                    }
                }
                val color = domainColor(domain)
                drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                end?.let { drawCircle(color.copy(alpha = .2f), 14.dp.toPx(), it); drawCircle(color, 7.dp.toPx(), it) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(first?.toString() ?: "기준을 쌓는 중이에요", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            Text(last?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Domain.entries.forEach { Text(domainName(it), style = MaterialTheme.typography.bodySmall, color = domainColor(it)) }
        }
    }
}

@Composable
fun DistanceChart(months: List<BlueLinkMonth>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("이전 차의 월별 주행 거리", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = "블루링크 모의 기록 ${months.size}개월 주행 거리" }) {
            if (months.isEmpty()) return@Canvas
            val max = months.maxOf { it.distanceKm }.coerceAtLeast(1.0)
            val step = size.width / months.size
            months.forEachIndexed { i, m ->
                val height = (m.distanceKm / max * size.height).toFloat()
                drawRoundRect(DarkTokens.replay.copy(alpha = .6f), Offset(step*i, size.height-height), Size(step*.65f, height), CornerRadius(4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(months.firstOrNull()?.month?.toString() ?: "모의 기록을 준비하고 있어요", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            months.lastOrNull()?.let { Text("${it.month} · ${it.distanceKm.toInt()} km", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
        }
    }
}
