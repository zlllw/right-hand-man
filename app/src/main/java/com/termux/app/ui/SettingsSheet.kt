package com.termux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.ui.theme.AppearanceMode
import com.termux.app.ui.theme.LocalRhmColors
import com.termux.app.ui.theme.RhmAppearance
import com.termux.app.ui.theme.ThemePreset

private fun modeLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.LIGHT -> "浅色模式"
    AppearanceMode.DARK -> "深色模式"
    AppearanceMode.SYSTEM -> "跟随系统"
}

private fun ThemePreset.swatchColor(dark: Boolean): Color = when (this) {
    ThemePreset.BLUE -> if (dark) Color(0xFF6EA8FF) else Color(0xFF2563EB)
    ThemePreset.TEAL -> if (dark) Color(0xFF5FD3C0) else Color(0xFF0E7C6B)
    ThemePreset.CLAY -> if (dark) Color(0xFFE89B6D) else Color(0xFFB4582A)
    ThemePreset.GRAPHITE -> if (dark) Color(0xFFDADCE6) else Color(0xFF35373F)
}

/**
 * 设置面板（对应 demo 的“外观 + 关于”半屏面板）。
 * 外观改动直接写入 [RhmAppearance]，由 MainActivity 收集后重建整套配色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val colors = LocalRhmColors.current
    val mode by RhmAppearance.mode.collectAsState()
    val preset by RhmAppearance.theme.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.line) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.64f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("设置", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)

            SectionLabel("外观")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                SettingLabel(title = "颜色模式", description = "跟随系统或手动切换", modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, colors.line, RoundedCornerShape(999.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ModeButton(RhmIcons.Sun, AppearanceMode.LIGHT, mode)
                    ModeButton(RhmIcons.Moon, AppearanceMode.DARK, mode)
                    ModeButton(RhmIcons.Monitor, AppearanceMode.SYSTEM, mode)
                }
            }
            HorizontalDivider(color = colors.line)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                SettingLabel(title = "主题色", description = preset.label, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThemePreset.entries.forEach { item ->
                        val selected = item == preset
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { RhmAppearance.setTheme(item) }
                                .then(
                                    if (selected) Modifier.border(2.dp, colors.accent, CircleShape)
                                    else Modifier.border(1.dp, colors.line, CircleShape)
                                )
                                .padding(if (selected) 4.dp else 3.dp)
                                .clip(CircleShape)
                                .background(item.swatchColor(colors.dark))
                                .semantics { contentDescription = item.label + "主题" }
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.line)

            SectionLabel("关于")
            AboutRow("版本", "0.1.0 · 原型")
            HorizontalDivider(color = colors.line)
            AboutRow("工作区", "~/rhmdir")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalRhmColors.current
    Text(
        text = text,
        fontSize = 11.sp,
        color = colors.faint,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingLabel(title: String, description: String, modifier: Modifier = Modifier) {
    val colors = LocalRhmColors.current
    Column(modifier = modifier) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.ink)
        Text(description, fontSize = 11.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ModeButton(icon: ImageVector, value: AppearanceMode, current: AppearanceMode) {
    val colors = LocalRhmColors.current
    val selected = value == current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable { RhmAppearance.setMode(value) }
            .semantics { contentDescription = modeLabel(value) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.accent else colors.muted,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val colors = LocalRhmColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = colors.muted)
    }
}
