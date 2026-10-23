package ai.handleon.car.ui.components

import ai.handleon.car.ui.RingUi
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RingGauge(rings: List<RingUi>, modifier: Modifier = Modifier, compact: Boolean = false) {
    val available = rings.any { it.fraction != null }
    val count = rings.count { it.over }
    val description = rings.joinToString(". ") { "${domainName(it.domain)}: ${when {
        it.fraction == null -> "기록을 모으는 중"; it.over -> "평소 범위를 넘었어요"; else -> "평소와 비슷해요"
    }}" }
    Box(modifier.size(if (compact) 260.dp else 340.dp).semantics { contentDescription = description }, Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.minDimension / if (compact) 260f else 340f
            val radii = if (compact) listOf(120f, 96f, 72f) else listOf(150f, 118f, 86f)
            val stroke = Stroke((if (compact) 18f else 22f) * unit, cap = StrokeCap.Round)
            radii.forEachIndexed { i, radius ->
                val r = radius * unit
                val offset = center - Offset(r, r)
                drawArc(Color(0x12FFFFFF), 135f, 270f, false, offset, Size(2*r, 2*r), style = stroke)
                rings.getOrNull(i)?.let { ring -> ring.fraction?.takeIf { it > 0 }?.let {
                    drawArc(if (ring.over) domainColor(ring.domain) else DarkTokens.hold,
                        135f, 270f * it, false, offset, Size(2*r, 2*r), style = stroke)
                } }
            }
            if (available) {
                val start = if (compact) Offset(156.3f, 78.3f) else Offset(201.8f, 107.6f)
                val end = if (compact) Offset(190.4f, 11.5f) else Offset(245.4f, 22.1f)
                drawLine(DarkTokens.threshold.copy(alpha = .7f), start * unit, end * unit, 3f * unit,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * unit, 5f * unit)))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (available) "$count" else "—", color = DarkTokens.ink,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = if (compact) 64.sp else 56.sp))
            Text(if (available) "영역이 달라요" else "기록을 모아요", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
    }
}

@Composable
fun CollectionRing(days: Int) {
    Box(Modifier.size(160.dp).semantics { contentDescription = "${days.coerceIn(0, 7)} / 7 운전일" }, Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            drawArc(Color(0x12FFFFFF), -90f, 360f, false, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
            if (days > 0) drawArc(DarkTokens.brand, -90f, 360f * days.coerceIn(0, 7) / 7f, false,
                style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${days.coerceIn(0, 7)} / 7", style = MaterialTheme.typography.titleLarge, color = DarkTokens.ink)
            Text("운전일", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
    }
}

/** Decorative onboarding SVG, not a report or a fabricated reading. */
@Composable
fun WelcomeGauge() {
    Box(Modifier.size(260.dp), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.width / 340f
            val stroke = Stroke(22f * unit, cap = StrokeCap.Round)
            listOf(150f, 118f, 86f).forEach { radius ->
                val r = radius * unit
                drawArc(Color(0x12FFFFFF), 135f, 270f, false, center - Offset(r, r), Size(2*r, 2*r), style = stroke)
            }
            val r = 150f * unit
            drawArc(DarkTokens.brand, 135f, 94.5f, false, center - Offset(r, r), Size(2*r, 2*r), style = stroke)
        }
        Icon(MidnightIcons.Wheel, null, Modifier.size(46.dp), tint = Color(0xFF8FF0D9))
    }
}
