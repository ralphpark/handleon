package ai.handleon.car.ui.components

import ai.handleon.car.ui.ReasonUi
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReasonRow(reason: ReasonUi, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        val icon = when {
            reason.feature == FeatureId.M4 -> MidnightIcons.Moon
            reason.domain == Domain.MOBILITY -> MidnightIcons.Pin
            reason.domain == Domain.WAYFINDING -> MidnightIcons.Route
            reason.feature == FeatureId.O7 -> MidnightIcons.Wheel
            else -> MidnightIcons.Turn
        }
        IconTile(icon, domainColor(reason.domain))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reason.line, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 27.sp, lineHeight = 38.sp, fontWeight = FontWeight.W500), color = DarkTokens.ink)
            Text(reason.evidence, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
    }
}

@Composable
fun CenterBanner(onCenter: () -> Unit, wide: Boolean) {
    val decoration = Modifier.fillMaxWidth().background(
        Brush.horizontalGradient(listOf(DarkTokens.talkFill.copy(alpha = .12f), DarkTokens.talkFill.copy(alpha = .03f))), RoundedCornerShape(28.dp))
        .border(1.dp, DarkTokens.talkFill.copy(alpha = .25f), RoundedCornerShape(28.dp)).padding(horizontal = 28.dp, vertical = 22.dp)
    if (wide) Row(decoration, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("가까운 센터에서 가족과 함께 상담받으실 수 있어요", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = DarkTokens.ink)
        PillButton("가까운 곳 보기", onCenter, kind = PillKind.CORAL, icon = MidnightIcons.Next)
    } else Column(decoration, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("가까운 센터에서 가족과 함께 상담받으실 수 있어요", style = MaterialTheme.typography.bodyMedium, color = DarkTokens.ink)
        PillButton("가까운 곳 보기", onCenter, Modifier.fillMaxWidth(), PillKind.CORAL, icon = MidnightIcons.Next)
    }
}

@Composable
fun ReplayNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().background(DarkTokens.replaySoft, RoundedCornerShape(20.dp)).padding(24.dp),
        style = MaterialTheme.typography.bodySmall, color = DarkTokens.replay)
}
