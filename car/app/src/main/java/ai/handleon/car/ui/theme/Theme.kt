package ai.handleon.car.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 클로드 디자인 "핸들온 HandleOn" 디자인 시스템 tokens.json(미드나잇, v2)의 색. 야간이 기본, 주간은 Pleos 조명 전환을 따른다. */
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

val LocalHandleOnColors = staticCompositionLocalOf { LightTokens }

// 차량 글자 크기: 디자인 시스템 "차량" 그룹 (최소 18px, Pleos 가이드)
private val CarTypography = Typography(
    displayLarge = TextStyle(fontSize = 58.sp, lineHeight = 70.sp, fontWeight = FontWeight.ExtraBold),
    headlineLarge = TextStyle(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 28.sp, lineHeight = 40.sp),
    bodyMedium = TextStyle(fontSize = 24.sp, lineHeight = 34.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    bodySmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
)

@Composable
fun HandleOnTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(primary = t.brand, onPrimary = t.onBrand, background = t.surface, onBackground = t.ink,
            surface = t.surfaceRaised, onSurface = t.ink, onSurfaceVariant = t.inkMuted, outline = t.line)
    } else {
        lightColorScheme(primary = t.brand, onPrimary = t.onBrand, background = t.surface, onBackground = t.ink,
            surface = t.surfaceRaised, onSurface = t.ink, onSurfaceVariant = t.inkMuted, outline = t.line)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalHandleOnColors provides t) {
        MaterialTheme(colorScheme = scheme, typography = CarTypography, content = content)
    }
}
