package com.termux.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手信配色（对应 site/rhm-mobile-demo.html 的设计定稿）：
 * 两套明暗底色 × 四套主题色（黛蓝/松绿/赭陶/石墨）。
 *
 * 玻璃材质目前用半透明色模拟（Compose 无背景模糊能力，真模糊需 Haze 库，后续增强）。
 */
data class RhmColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val field: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val accentSoftInk: Color,
    val accentLine: Color,
    val glassBg: Color,
    val glassLine: Color,
    val ok: Color,
    val danger: Color
)

private data class AccentSet(
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val accentSoftInk: Color,
    val accentLine: Color
)

private fun accentFor(preset: ThemePreset, dark: Boolean): AccentSet = when (preset) {
    ThemePreset.BLUE -> if (dark) {
        AccentSet(Color(0xFF6EA8FF), Color(0xFF0B1B33), Color(0xFF16263D), Color(0xFFBBD4FF), Color(0xFF2C4468))
    } else {
        AccentSet(Color(0xFF2563EB), Color.White, Color(0xFFE8F0FE), Color(0xFF1D4ED8), Color(0xFFBFD4F7))
    }
    ThemePreset.TEAL -> if (dark) {
        AccentSet(Color(0xFF5FD3C0), Color(0xFF08221D), Color(0xFF17332E), Color(0xFFA9E8DC), Color(0xFF28514A))
    } else {
        AccentSet(Color(0xFF0E7C6B), Color.White, Color(0xFFE3F3EF), Color(0xFF0B5F52), Color(0xFFBDE3DA))
    }
    ThemePreset.CLAY -> if (dark) {
        AccentSet(Color(0xFFE89B6D), Color(0xFF2B170C), Color(0xFF38271C), Color(0xFFF3C4A3), Color(0xFF5A3D2A))
    } else {
        AccentSet(Color(0xFFB4582A), Color.White, Color(0xFFF8ECE3), Color(0xFF8E4520), Color(0xFFECCDB8))
    }
    ThemePreset.GRAPHITE -> if (dark) {
        AccentSet(Color(0xFFDADCE6), Color(0xFF17181D), Color(0xFF2A2B33), Color(0xFFE4E5EC), Color(0xFF41434E))
    } else {
        AccentSet(Color(0xFF35373F), Color.White, Color(0xFFEAEAF0), Color(0xFF33353D), Color(0xFFCED0DA))
    }
}

fun rhmColors(dark: Boolean, preset: ThemePreset): RhmColors {
    val a = accentFor(preset, dark)
    return if (dark) {
        RhmColors(
            dark = true,
            background = Color(0xFF0F0F13),
            surface = Color(0xFF17171C),
            surface2 = Color(0xFF1F1F26),
            ink = Color(0xFFEDEDF2),
            ink2 = Color(0xFFC2C3CE),
            muted = Color(0xFF8F91A0),
            faint = Color(0xFF63656F),
            line = Color(0xFF26262F),
            field = Color(0xFF1D1D24),
            accent = a.accent,
            accentInk = a.accentInk,
            accentSoft = a.accentSoft,
            accentSoftInk = a.accentSoftInk,
            accentLine = a.accentLine,
            glassBg = Color(0xA81E1E26),
            glassLine = Color(0x14FFFFFF),
            ok = Color(0xFF63D3A9),
            danger = Color(0xFFF0909A)
        )
    } else {
        RhmColors(
            dark = false,
            background = Color(0xFFF5F5F8),
            surface = Color.White,
            surface2 = Color(0xFFF3F3F7),
            ink = Color(0xFF1D1E26),
            ink2 = Color(0xFF4B4D59),
            muted = Color(0xFF7C7E8C),
            faint = Color(0xFFA7A9B5),
            line = Color(0xFFE8E8EF),
            field = Color(0xFFF1F1F6),
            accent = a.accent,
            accentInk = a.accentInk,
            accentSoft = a.accentSoft,
            accentSoftInk = a.accentSoftInk,
            accentLine = a.accentLine,
            glassBg = Color(0xB8FFFFFF),
            glassLine = Color(0x14181A2C),
            ok = Color(0xFF1B7A5F),
            danger = Color(0xFFB23F49)
        )
    }
}

val LocalRhmColors = staticCompositionLocalOf { rhmColors(false, ThemePreset.GRAPHITE) }

@Composable
fun RhmTheme(mode: AppearanceMode, preset: ThemePreset, content: @Composable () -> Unit) {
    val dark = when (mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val colors = remember(dark, preset) { rhmColors(dark, preset) }
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.accentSoftInk,
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.accentSoftInk,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.ink2,
            outline = colors.line,
            outlineVariant = colors.line,
            error = colors.danger,
            errorContainer = Color(0xFF3A2024),
            onErrorContainer = Color(0xFFF0909A)
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.accentSoftInk,
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.accentSoftInk,
            background = colors.background,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.ink2,
            outline = colors.line,
            outlineVariant = colors.line,
            error = colors.danger,
            errorContainer = Color(0xFFF9E3E5),
            onErrorContainer = Color(0xFFB23F49)
        )
    }
    CompositionLocalProvider(LocalRhmColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 顶部淡出遮罩：内容滑到顶边时逐渐变淡（对应 demo 的 mask-image 渐变）。 */
fun Modifier.rhmFadeTop(fadeHeight: Dp = 28.dp): Modifier = this
    // DstOut 必须在离屏层内合成，否则会把窗口背景当成擦除目标（真机实测出现黑色渐变条）
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = fadeHeight.toPx()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, endY = h),
            size = Size(size.width, h),
            blendMode = BlendMode.DstOut
        )
    }
