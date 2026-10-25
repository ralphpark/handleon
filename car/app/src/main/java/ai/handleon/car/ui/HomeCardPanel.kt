package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One horizontal card. The caller owns all state, lifecycle and side effects. */
@Composable
fun HomeCardPanel(card: HomeCard, busy: Boolean, onStartTimer: () -> Unit, onStopTimer: () -> Unit,
                  onNoteSeen: (Long) -> Unit, onDismissLine: () -> Unit, onCenter: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                val title = when (card) {
                    is HomeCard.Rest -> card.ui.title
                    is HomeCard.Note -> card.ui.title
                    is HomeCard.Line -> card.ui.title
                }
                val body = when (card) {
                    is HomeCard.Rest -> card.ui.body
                    is HomeCard.Note -> card.ui.body
                    is HomeCard.Line -> card.ui.body
                }
                Text(title, style = MaterialTheme.typography.titleSmall, color = DarkTokens.ink)
                Text(body, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when (card) {
                is HomeCard.Rest -> if (card.ui.remainingMs != null) PillButton("그만", onStopTimer, kind = PillKind.SECONDARY)
                    else if (!card.ui.finished) PillButton("15분 타이머", onStartTimer)
                is HomeCard.Note -> PillButton("확인했어요", { onNoteSeen(card.ui.id) }, enabled = !busy)
                is HomeCard.Line -> {
                    if (card.ui.center) PillButton("가까운 곳 보기", onCenter, kind = PillKind.CORAL)
                    PillButton("오늘은 그만 보기", onDismissLine, kind = PillKind.SECONDARY)
                }
            }
        }
        if (card is HomeCard.Line) Text(TODAY_LINE_NOTICE, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkFaint)
    }
}
