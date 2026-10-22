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

/** 클로드 디자인 "핸들온 HandleOn" 디자인 시스템 tokens.json 의 색. 주간/야간. */
@Immutable
data class HandleOnColors(
    val surface: Color, val surfaceRaised: Color, val surfaceSunken: Color, val line: Color,
    val ink: Color, val inkMuted: Color, val brand: Color, val onBrand: Color, val brandSoft: Color,
    val stable: Color, val watchFill: Color, val onWatch: Color, val watchInk: Color,
    val talkFill: Color, val onTalk: Color, val talkInk: Color, val threshold: Color,
    val replay: Color, val replaySoft: Color, val focus: Color,
)

val LightTokens = HandleOnColors(
    surface = Color(0xFFF6F5F1), surfaceRaised = Color(0xFFFFFFFF), surfaceSunken = Color(0xFFECEAE3), line = Color(0xFFD3D0C6),
    ink = Color(0xFF1C1F1D), inkMuted = Color(0xFF565E59), brand = Color(0xFF1F5C4A), onBrand = Color(0xFFFFFFFF), brandSoft = Color(0xFFDCEBE4),
    stable = Color(0xFF2C7A57), watchFill = Color(0xFFF2B84B), onWatch = Color(0xFF1C1F1D), watchInk = Color(0xFF855800),
    talkFill = Color(0xFFD9774B), onTalk = Color(0xFF1C1F1D), talkInk = Color(0xFFA3441C), threshold = Color(0xFF565E59),
    replay = Color(0xFF2B5FB4), replaySoft = Color(0xFFE3ECFA), focus = Color(0xFF2B5FB4),
)

val DarkTokens = HandleOnColors(
    surface = Color(0xFF121513), surfaceRaised = Color(0xFF1C201D), surfaceSunken = Color(0xFF0B0D0C), line = Color(0xFF323833),
    ink = Color(0xFFEEF0EC), inkMuted = Color(0xFFA7AFAA), brand = Color(0xFF7CC4A5), onBrand = Color(0xFF0C1F18), brandSoft = Color(0xFF1D3A30),
    stable = Color(0xFF6CC79A), watchFill = Color(0xFFE0A93E), onWatch = Color(0xFF1C1F1D), watchInk = Color(0xFFF2C36A),
    talkFill = Color(0xFFE08A62), onTalk = Color(0xFF1C1F1D), talkInk = Color(0xFFF0A584), threshold = Color(0xFFA7AFAA),
    replay = Color(0xFF8FB3F2), replaySoft = Color(0xFF1B2A44), focus = Color(0xFF8FB3F2),
)

val LocalHandleOnColors = staticCompositionLocalOf { LightTokens }

// 차량 글자 크기: 디자인 시스템 "차량" 그룹 (최소 18px, Pleos 가이드)
private val CarTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold),
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
