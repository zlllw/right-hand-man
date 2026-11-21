package com.termux.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 运行环境面板（任务书阶段 1 的验收点：进度与失败原因都要在 UI 上看得见）。
 *
 * 纯粹吃 [EnvViewModel.UiState]，自己只留一个「日志展开」的 UI 局部状态，
 * 因此可以 `@Preview` 喂任意阶段。
 */
@Composable
fun EnvPanel(
    state: EnvViewModel.UiState,
    onPrepare: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state.phase) {
        EnvViewModel.Phase.CHECKING -> Hint("正在检查运行环境…", modifier)
        EnvViewModel.Phase.READY -> Hint("运行环境：${state.summary}", modifier)
        EnvViewModel.Phase.MISSING -> Notice(
            title = "运行环境未就绪",
            body = "首次使用要先装 python 与 node：约 120 MB 下载，3–6 分钟，需要联网。",
            actionLabel = "准备运行环境",
            onAction = onPrepare,
            modifier = modifier
        )

        EnvViewModel.Phase.FAILED -> Notice(
            title = state.summary.ifBlank { "运行环境准备失败" },
            body = state.detail ?: "原因未知，请查看日志",
            actionLabel = "重试",
            onAction = onPrepare,
            isError = true,
            modifier = modifier
        )

        EnvViewModel.Phase.RUNNING -> Running(state, modifier)
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
private fun Notice(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(color = container, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(text = body, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onAction, modifier = Modifier.padding(start = 8.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun Running(state: EnvViewModel.UiState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val lastLine = state.lines.lastOrNull()?.takeIf { it.isNotBlank() }

    LaunchedEffect(state.lines.size) {
        if (expanded && state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = state.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = lastLine ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起日志" else "查看日志")
                }
            }
            if (expanded) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(state.lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ---- Preview：三个阶段各一份，喂假数据、不跑脚本（契约 C 的 C4） ----

@Preview(name = "缺运行环境", showBackground = true)
@Composable
private fun PreviewMissing() {
    MaterialTheme {
        EnvPanel(state = EnvViewModel.UiState(EnvViewModel.Phase.MISSING), onPrepare = {})
    }
}

@Preview(name = "安装中", showBackground = true)
@Composable
private fun PreviewRunning() {
    MaterialTheme {
        EnvPanel(
            state = EnvViewModel.UiState(
                phase = EnvViewModel.Phase.RUNNING,
                summary = "正在准备运行环境",
                lines = listOf("==> 更新包索引", "==> 改写软件包内的路径前缀", "    改写 104 个文件：python_3.14.10_aarch64.deb")
            ),
            onPrepare = {}
        )
    }
}

@Preview(name = "失败", showBackground = true)
@Composable
private fun PreviewFailed() {
    MaterialTheme {
        EnvPanel(
            state = EnvViewModel.UiState(
                phase = EnvViewModel.Phase.FAILED,
                summary = "运行环境准备失败",
                detail = "apt update 失败（检查网络或源可达性）"
            ),
            onPrepare = {}
        )
    }
}