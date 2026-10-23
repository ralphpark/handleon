package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.*

data class LetterUi(val month: YearMonth, val text: String) {
    val title: String get() = "${month.monthValue}월 운전 편지"
    val preview: String get() = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    companion object {
        fun fromJson(json: String?): LetterUi? = try {
            val row = Json.parseToJsonElement(json ?: "{}").jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            if (text.isBlank()) null else LetterUi(YearMonth.from(LocalDate.parse(row.getValue("month").jsonPrimitive.content)), text)
        } catch (_: Exception) { null }
    }
}

@Composable
fun LetterScreen(letter: LetterUi?, onBack: () -> Unit) {
    ScreenFrame(notice = true) {
        PageHeader(letter?.title ?: "운전 편지", onBack)
        GlassPanel(Modifier.fillMaxWidth(), padding = 36.dp) {
            Text(letter?.text ?: "아직 받은 편지가 없어요.", style = MaterialTheme.typography.bodyLarge, color = DarkTokens.ink)
        }
    }
}

@Composable
internal fun LetterCard(letter: LetterUi, onRead: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().background(
        Brush.linearGradient(listOf(DarkTokens.brand.copy(alpha = .08f), Color.White.copy(alpha = .02f))), RoundedCornerShape(28.dp))
        .border(1.dp, DarkTokens.brand.copy(alpha = .2f), RoundedCornerShape(28.dp))) {
        BoxWithConstraints {
            if (maxWidth >= 600.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LetterPreview(letter, Modifier.weight(1f))
                PillButton("편지 읽기", onRead, kind = PillKind.SECONDARY)
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LetterPreview(letter)
                PillButton("편지 읽기", onRead, kind = PillKind.SECONDARY)
            }
        }
    }
}

@Composable
private fun LetterPreview(letter: LetterUi, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(letter.title, style = MaterialTheme.typography.labelSmall, color = DarkTokens.brand)
        Text(letter.preview, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
