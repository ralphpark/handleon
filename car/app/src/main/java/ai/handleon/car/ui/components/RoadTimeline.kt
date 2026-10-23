package ai.handleon.car.ui.components

import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.Stage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import kotlin.math.sin

@Composable
fun RoadTimeline(stages: List<Stage>, start: YearMonth?, total: Int = 24) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("운전 길 · ${stages.size} / $total 개월", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "$total 개월 운전 길 중 ${stages.size}개월 재생" }) {
            // Same sine-shaped 1584 × 220 road as Replay.dc.html.
            fun point(x: Float) = Offset(x / 1584f * size.width, (96f + 44f * sin((x - 32f) / 86f)) / 220f * size.height)
            val road = Path().apply { moveTo(point(0f).x, point(0f).y); for (x in 16..1584 step 16) { val p=point(x.toFloat()); lineTo(p.x,p.y) } }
            drawPath(road, Color(0x17FFFFFF), style = Stroke(42.dp.toPx(), cap = StrokeCap.Round))
            drawPath(road, Color(0x47FFFFFF), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(),12.dp.toPx()))))
            repeat(total) { i ->
                val p = point(48f + i * 1488f / (total - 1).coerceAtLeast(1))
                val color = stages.getOrNull(i)?.let { if (it == Stage.BUILDING) Color(0xFF8D93C4) else stageColor(it) }
                if (color == null) { drawCircle(DarkTokens.surface, 7.dp.toPx(), p); drawCircle(Color(0x33FFFFFF), 7.dp.toPx(), p, style = Stroke(2.dp.toPx())) }
                else { drawCircle(color.copy(alpha=.2f), (if (i == stages.lastIndex) 28 else 14).dp.toPx(), p); drawCircle(color, (if (i == stages.lastIndex) 10 else 8).dp.toPx(), p) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(start?.let { "${it.year}년 ${it.monthValue}월 · 기준 기간부터" } ?: "재생 준비 중", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            Text(start?.plusMonths(total.toLong()-1)?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
    }
}
