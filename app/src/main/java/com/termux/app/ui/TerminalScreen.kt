package com.termux.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 终端页的**临时落点**（阶段 1b 第一刀）。
 *
 * 为什么不在这里直接 `AndroidView` 包一层 `activity_termux.xml`：
 * `TermuxActivityRootView` 持有 `TermuxActivity` 实例（`setActivity()`），
 * `TerminalView` 的 `TerminalViewClient` / `TerminalSession` / 服务绑定全部由
 * `TermuxActivity.onCreate` 建起来。光把布局 inflate 出来只会得到一个**空白终端**——
 * 看起来像终端、点进去不能执行命令，比明确的占位更糟。
 *
 * 正确落点（任务书阶段 1b 的推荐方案）：把 `TermuxActivity` 的初始化抽成一个可复用的
 * 宿主视图，`MainActivity` 与 `TermuxActivity` 共用，再用 `AndroidView` 嵌进来。
 * 那是下一步的独立改动，不硬约束名单里的代码不受影响。
 */
@Composable
fun TerminalScreen(
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "终端页尚未嵌入",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "终端视图需要 TermuxActivity 的初始化（会话、服务绑定、键盘处理），" +
                "抽成可复用宿主后才能真正嵌进这一页。当前可先进入原终端页使用完整功能。",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Button(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) {
            Text("打开终端（原 TermuxActivity）")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewTerminalPlaceholder() {
    MaterialTheme {
        TerminalScreen(onOpenTerminal = {})
    }
}