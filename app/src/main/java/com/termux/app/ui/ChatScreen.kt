package com.termux.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.ChatViewModel.Role

/**
 * 对话页（契约 C 的 L4：无状态、可预览）。
 *
 * 只吃 [ChatViewModel.UiState]，不持有 ViewModel，因此 `@Preview` 与 UI 测试都能直接喂数据。
 * 协议/网络相关的一切都在 Repository 与 ViewModel 里，这里不碰。
 */
@Composable
fun ChatScreen(
    state: ChatViewModel.UiState,
    bridgeConnected: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 有新消息就滚到底（契约 C 的 C4：用状态而不是轮询）
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        if (!bridgeConnected) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = "未连接 Bridge：聊天内容来自本地演示适配器，不会真的调用 Agent",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.messages.isEmpty()) {
                item {
                    Text(
                        text = "说点什么，Agent 会在终端里执行",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                    )
                }
            }
            items(state.messages) { message ->
                MessageBubble(message)
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("发消息…") },
                maxLines = 4
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        draft = ""
                    }
                },
                enabled = !state.streaming && draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(if (state.streaming) "…" else "发送")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatViewModel.Message) {
    val isUser = message.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = message.text.ifEmpty { if (message.streaming) "…" else "" })
            }
        }
    }
}

// ---- Preview：每种状态都要有，喂假数据、不联网（契约 C 的 C4） ----

@Preview(name = "空态", showBackground = true)
@Composable
private fun PreviewEmpty() {
    MaterialTheme {
        ChatScreen(state = ChatViewModel.UiState(), bridgeConnected = true, onSend = {})
    }
}

@Preview(name = "流式中", showBackground = true)
@Composable
private fun PreviewStreaming() {
    MaterialTheme {
        ChatScreen(
            state = ChatViewModel.UiState(
                messages = listOf(
                    ChatViewModel.Message(Role.USER, "列一下家目录"),
                    ChatViewModel.Message(Role.ASSISTANT, "正在执行 ls ~", streaming = true)
                ),
                streaming = true
            ),
            bridgeConnected = true,
            onSend = {}
        )
    }
}

@Preview(name = "未连接 Bridge", showBackground = true)
@Composable
private fun PreviewDisconnected() {
    MaterialTheme {
        ChatScreen(
            state = ChatViewModel.UiState(
                messages = listOf(ChatViewModel.Message(Role.ASSISTANT, "echo: 演示模式")),
                error = "连接中断：Connection refused"
            ),
            bridgeConnected = false,
            onSend = {}
        )
    }
}