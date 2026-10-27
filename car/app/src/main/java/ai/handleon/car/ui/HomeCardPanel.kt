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
                  onNoteSeen: (Long) -> Unit, onDismissLine: () -> Unit, onCenter: () -> Unit,
                  onDismissRest: () -> Unit = {}, onDriverAnswer: (Long, Boolean) -> Unit = { _, _ -> },
                  onConfirm: () -> Unit = {}, onCancelConfirm: () -> Unit = {}, onResume: () -> Unit = {},
                  onDismissTrip: () -> Unit = {}, onDismissWeek: () -> Unit = {}) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                val title = when (card) {
                    is HomeCard.Rest -> card.ui.title
                    is HomeCard.DriverCheck -> "운전자 확인"
                    is HomeCard.Confirm -> "확인해 주세요"
                    is HomeCard.LostWay -> "집까지 안내해 드릴까요?"
                    is HomeCard.Trip -> "오늘 운전"
                    is HomeCard.Week -> "이번 주 이야기"
                    is HomeCard.Note -> card.ui.title
                    is HomeCard.Line -> card.ui.title
                }
                val body = when (card) {
                    is HomeCard.Rest -> card.ui.body
                    is HomeCard.DriverCheck -> "방금 운전하신 분이 등록된 운전자 본인인가요?"
                    is HomeCard.Confirm -> when (card.action) {
                        PendingConfirm.MarkOtherDriver -> "이번엔 다른 분이 운전하셨나요?"
                        PendingConfirm.PauseSharing -> "가족 공유를 잠시 멈출까요?"
                    }
                    is HomeCard.LostWay -> "주차한 뒤 길안내를 준비해요"
                    is HomeCard.Trip -> card.line
                    is HomeCard.Week -> card.story
                    is HomeCard.Note -> card.ui.body
                    is HomeCard.Line -> card.ui.body
                }
                Text(title, style = MaterialTheme.typography.titleSmall, color = DarkTokens.ink)
                Text(body, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when (card) {
                is HomeCard.Rest -> if (card.ui.remainingMs != null) PillButton("그만", onStopTimer, kind = PillKind.SECONDARY)
                    else {
                        if (!card.ui.finished) PillButton("15분 타이머", onStartTimer)
                        // 쉬지 않을 때도 한마디·오늘의 한 줄을 가리지 않게 이번 운전에서는 닫는다.
                        PillButton("괜찮아요", onDismissRest, kind = PillKind.SECONDARY)
                    }
                is HomeCard.DriverCheck -> {
                    PillButton("네, 저예요", { onDriverAnswer(card.startMs, true) }, enabled = !busy)
                    PillButton("다른 분이에요", { onDriverAnswer(card.startMs, false) }, kind = PillKind.SECONDARY, enabled = !busy)
                }
                is HomeCard.Confirm -> {
                    PillButton("확인", onConfirm, enabled = !busy)
                    PillButton("취소", onCancelConfirm, kind = PillKind.SECONDARY, enabled = !busy)
                }
                is HomeCard.Trip -> {
                    PillButton("내 운전 이력서", onResume)
                    PillButton("닫기", onDismissTrip, kind = PillKind.SECONDARY)
                }
                is HomeCard.Week -> {
                    PillButton("내 운전 이력서", onResume)
                    PillButton("이번 주 그만 보기", onDismissWeek, kind = PillKind.SECONDARY)
                }
                is HomeCard.LostWay -> Unit // 생성과 길안내는 C8에서 연결한다.
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
