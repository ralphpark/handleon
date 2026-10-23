package ai.handleon.car.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import ai.handleon.car.R
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 클로드 디자인 "핸들온 HandleOn" 디자인 시스템 tokens.json(미드나잇, v2)의 색. 차량 앱은 항상 야간으로 그린다. */
@Immutable
data class HandleOnColors(
    val surface: Color, val surfaceRaised: Color, val surfaceSunken: Color, val line: Color,
    val ink: Color, val inkMuted: Color, val brand: Color, val onBrand: Color, val brandSoft: Color,
    val stable: Color, val watchFill: Color, val onWatch: Color, val watchInk: Color,
    val talkFill: Color, val onTalk: Color, val talkInk: Color, val threshold: Color,
    val replay: Color, val replaySoft: Color, val focus: Color,
    val surfaceStrong: Color, val inkFaint: Color, val hold: Color, val glowBrand: Color, val glowTalk: Color,
    val domainMobility: Color, val domainWayfinding: Color, val domainOperation: Color,
)

val LightTokens = HandleOnColors(
    surface = Color(0xFFF3F5F7), surfaceRaised = Color(0xFFFFFFFF), surfaceSunken = Color(0xFFE7EBF0), line = Color(0xFFDCE2E8),
    ink = Color(0xFF0F1720), inkMuted = Color(0xFF56616D), brand = Color(0xFF0B7F6A), onBrand = Color(0xFFFFFFFF), brandSoft = Color(0xFFDDF4EE),
    stable = Color(0xFF0B7F6A), watchFill = Color(0xFFE9A23B), onWatch = Color(0xFF1A1205), watchInk = Color(0xFF8A5600),
    talkFill = Color(0xFFE0663F), onTalk = Color(0xFF1C0D07), talkInk = Color(0xFFA63F1C), threshold = Color(0xFF56616D),
    replay = Color(0xFF2457C5), replaySoft = Color(0xFFE3ECFA), focus = Color(0xFF2457C5),
    surfaceStrong = Color(0xFFFFFFFF), inkFaint = Color(0xFF6B7682), hold = Color(0xFF6B7682), glowBrand = Color(0xFFE6F7F3), glowTalk = Color(0xFFFBE9E2),
    domainMobility = Color(0xFF0B7F6A), domainWayfinding = Color(0xFF5B48D6), domainOperation = Color(0xFF8A6D1F),
)

val DarkTokens = HandleOnColors(
    surface = Color(0xFF0A0D12), surfaceRaised = Color(0xFF14181E), surfaceSunken = Color(0xFF070A0E), line = Color(0xFF262C34),
    ink = Color(0xFFF2F5F7), inkMuted = Color(0xFF9AA7B2), brand = Color(0xFF4FE3C1), onBrand = Color(0xFF06231D), brandSoft = Color(0xFF133A35),
    stable = Color(0xFF4FE3C1), watchFill = Color(0xFFFFC46B), onWatch = Color(0xFF1A1205), watchInk = Color(0xFFFFD38F),
    talkFill = Color(0xFFFF8A65), onTalk = Color(0xFF1C0D07), talkInk = Color(0xFFFFB59D), threshold = Color(0xFFC9D2DA),
    replay = Color(0xFF7FB2FF), replaySoft = Color(0xFF16233D), focus = Color(0xFF7FB2FF),
    surfaceStrong = Color(0xFF1B2027), inkFaint = Color(0xFF7D8994), hold = Color(0xFF7F8FA0), glowBrand = Color(0xFF123039), glowTalk = Color(0xFF2B1914),
    domainMobility = Color(0xFF4FE3C1), domainWayfinding = Color(0xFFA99CFF), domainOperation = Color(0xFFE8D5A3),
)

val LocalHandleOnColors = staticCompositionLocalOf { DarkTokens }

@OptIn(ExperimentalTextApi::class)
val AstaSans = FontFamily(
    Font(R.font.asta_sans, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.asta_sans, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.asta_sans, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.asta_sans, FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.asta_sans, FontWeight.W800, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private fun carText(size: Int, line: Int, weight: FontWeight = FontWeight.W400) =
    TextStyle(fontFamily = AstaSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
        letterSpacing = if (size >= 44) (-1).sp else 0.sp)

val CarTypography = Typography(
    displayLarge = carText(58, 70, FontWeight.W800),
    displayMedium = carText(58, 70, FontWeight.W800),
    displaySmall = carText(48, 58, FontWeight.W800),
    headlineLarge = carText(44, 56, FontWeight.W800),
    headlineMedium = carText(36, 46, FontWeight.W700),
    headlineSmall = carText(30, 40, FontWeight.W700),
    titleLarge = carText(34, 44, FontWeight.W600),
    titleMedium = carText(28, 40, FontWeight.W600),
    titleSmall = carText(24, 34, FontWeight.W600),
    bodyLarge = carText(28, 40),
    bodyMedium = carText(24, 34),
    bodySmall = carText(18, 26),
    labelLarge = carText(22, 30, FontWeight.W600),
    labelMedium = carText(20, 28, FontWeight.W600),
    labelSmall = carText(18, 26, FontWeight.W500),
)

@Composable
fun HandleOnTheme(content: @Composable () -> Unit) {
    // ponytail: Pleos 주간 전환은 나중에
    val t = DarkTokens
    val scheme = darkColorScheme(primary = t.brand, onPrimary = t.onBrand,
        background = t.surface, onBackground = t.ink, surface = t.surfaceRaised,
        onSurface = t.ink, onSurfaceVariant = t.inkMuted, outline = t.line)
    androidx.compose.runtime.CompositionLocalProvider(LocalHandleOnColors provides t) {
        MaterialTheme(colorScheme = scheme, typography = CarTypography, content = content)
    }
}
