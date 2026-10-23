package ai.handleon.car.ui.components

import ai.handleon.car.ui.DISCLAIMER
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.Domain
import ai.handleon.driveprint.Stage
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

fun domainColor(domain: Domain): Color = when (domain) {
    Domain.MOBILITY -> DarkTokens.domainMobility
    Domain.WAYFINDING -> DarkTokens.domainWayfinding
    Domain.OPERATION -> DarkTokens.domainOperation
}
fun domainName(domain: Domain) = when (domain) {
    Domain.MOBILITY -> "생활 반경"; Domain.WAYFINDING -> "길찾기"; Domain.OPERATION -> "조작"
}
fun stageColor(stage: Stage) = when (stage) {
    Stage.BUILDING -> DarkTokens.hold; Stage.STABLE -> DarkTokens.stable
    Stage.WATCH -> DarkTokens.watchFill; Stage.TALK -> DarkTokens.talkFill
}

@Composable
fun MidnightBackground(talk: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(DarkTokens.surface).drawBehind {
        fun glow(center: Offset, width: Float, height: Float, color: Color, stop: Float) {
            scale(1f, height / width, pivot = center) {
                drawCircle(Brush.radialGradient(0f to color, stop to Color.Transparent, 1f to Color.Transparent,
                    center = center, radius = width), width, center)
            }
        }
        val portrait = size.width < 1000.dp.toPx()
        glow(Offset(size.width * .12f, size.height * .08f), (if (portrait) 760 else 1100).dp.toPx(),
            (if (portrait) 560 else 700).dp.toPx(), DarkTokens.glowBrand, .62f)
        glow(Offset(size.width, size.height), (if (portrait) 620 else 900).dp.toPx(),
            (if (portrait) 560 else 620).dp.toPx(), if (talk) DarkTokens.glowTalk else Color(0xFF16202B), .60f)
    }, content = content)
}

@Composable
fun GlassPanel(modifier: Modifier = Modifier, radius: Dp = 28.dp, padding: Dp = 28.dp,
               contentPadding: PaddingValues = PaddingValues(padding),
               content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(radius)).background(Color.White.copy(alpha = .04f))
        .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(radius)).padding(contentPadding), content = content)
}

/** Shared press/focus treatment for pills, tiles, tabs and consent rows. */
fun Modifier.midnightClick(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    this.graphicsLayer { scaleX = if (pressed) .95f else 1f; scaleY = scaleX }
        .then(if (focused) Modifier.border(3.dp, DarkTokens.focus, RoundedCornerShape(20.dp)) else Modifier)
        .clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

enum class PillKind { PRIMARY, SECONDARY, CORAL, REPLAY }

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               kind: PillKind = PillKind.PRIMARY, enabled: Boolean = true, icon: ImageVector? = null) {
    val bg = when {
        !enabled -> Color.White.copy(alpha = .06f)
        kind == PillKind.PRIMARY -> DarkTokens.brand
        kind == PillKind.CORAL -> DarkTokens.talkFill
        kind == PillKind.REPLAY -> DarkTokens.replay
        else -> Color.White.copy(alpha = .06f)
    }
    val fg = when {
        !enabled -> DarkTokens.inkMuted
        kind == PillKind.PRIMARY -> DarkTokens.onBrand
        kind == PillKind.CORAL -> DarkTokens.onTalk
        kind == PillKind.REPLAY -> Color(0xFF0A1428)
        else -> DarkTokens.ink
    }
    Row(modifier.height(68.dp).widthIn(min = 64.dp).midnightClick(enabled, onClick = onClick)
        .clip(RoundedCornerShape(999.dp)).background(bg)
        .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(999.dp)).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
        icon?.let { Spacer(Modifier.width(10.dp)); Icon(it, null, Modifier.size(26.dp), tint = fg) }
    }
}

@Composable
fun IconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(64.dp).background(color.copy(alpha = .12f), RoundedCornerShape(20.dp)), Alignment.Center) {
        Icon(icon, null, Modifier.size(32.dp), tint = color)
    }
}

@Composable
fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(72.dp).semantics { contentDescription = label }.midnightClick(onClick = onClick)
        .background(Color(0x0FFFFFFF), RoundedCornerShape(24.dp))
        .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(24.dp)), Alignment.Center) {
        Icon(icon, null, Modifier.size(30.dp), tint = DarkTokens.ink)
    }
}

@Composable
fun StageDot(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .4f), Color.Transparent)), radius = 18.dp.toPx())
        drawCircle(color, 7.dp.toPx())
    }
}

@Composable
fun SegmentedControl(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
                     options: List<String> = listOf("이번 달", "지난 기록")) {
    Row(modifier.background(Color(0x0FFFFFFF), RoundedCornerShape(999.dp))
        .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(999.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, label ->
            Box(Modifier.heightIn(min = 64.dp).semantics { this.selected = selected == index }
                .midnightClick(role = Role.Tab) { onSelect(index) }
                .background(if (index == selected) DarkTokens.ink else Color.Transparent, RoundedCornerShape(999.dp))
                .padding(horizontal = 30.dp, vertical = 16.dp), Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (index == selected) DarkTokens.surface else DarkTokens.threshold)
            }
        }
    }
}

@Composable
fun Logo(size: Dp = 52.dp) {
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(DarkTokens.brand, Color(0xFF2A9D8F))),
        RoundedCornerShape(16.dp)), Alignment.Center) {
        Icon(MidnightIcons.Wheel, "핸들온 로고", Modifier.size(size * .58f), tint = DarkTokens.onBrand)
    }
}

@Composable
fun Disclaimer(modifier: Modifier = Modifier) {
    Text(DISCLAIMER, modifier, color = DarkTokens.inkFaint, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ScreenFrame(talk: Boolean = false, notice: Boolean = false, topBar: (@Composable (Boolean) -> Unit)? = null,
                footer: (@Composable (Boolean) -> Unit)? = null, content: @Composable ColumnScope.(Boolean) -> Unit) {
    MidnightBackground(talk) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1000.dp
            Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 40.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)) {
                topBar?.invoke(wide)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(28.dp)) { content(wide) }
                // 스크롤 밖에 두어 창 크기와 상관없이 늘 보인다(센터 띠).
                footer?.invoke(wide)
                if (notice) Disclaimer()
            }
        }
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        IconButton(MidnightIcons.Back, "뒤로", onBack)
        Text(title, style = MaterialTheme.typography.headlineLarge, color = DarkTokens.ink)
    }
}

@Composable
fun HomeHeader(title: String, period: String, onLabels: () -> Unit, onSettings: () -> Unit,
               trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Logo()
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 38.sp), color = DarkTokens.ink)
            Text(period, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
        trailing()
        IconButton(MidnightIcons.Tag, "라벨 붙이기", onLabels)
        IconButton(MidnightIcons.Settings, "설정", onSettings)
    }
}
