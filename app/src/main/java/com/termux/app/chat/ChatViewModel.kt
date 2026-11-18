package com.termux.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 对话状态机（契约 C 的 L3）。
 *
 * 只依赖 [ChatRepository]，**不依赖 Compose**——UI 换实现时这里一行不改。
 * 断线重连走 `?from=<lastEventId>`（契约 B 的 B5 第 3/4 条），只重试一次，
 * 不做任何"去重"启发式。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val conversationId: String = "default"
) : ViewModel() {

    enum class Role { USER, ASSISTANT }

    data class Message(
        val role: Role,
        val text: String,
        val streaming: Boolean = false
    )

    data class UiState(
        val messages: List<Message> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
        val lastEventId: Long? = null,
        val connected: Boolean = false
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    fun refreshHealth() {
        viewModelScope.launch {
            val healthy = runCatching { repository.health() }.getOrDefault(false)
            mutableState.value = mutableState.value.copy(connected = healthy)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || mutableState.value.streaming) return
        val current = mutableState.value
        mutableState.value = current.copy(
            messages = current.messages +
                Message(Role.USER, trimmed) +
                Message(Role.ASSISTANT, "", streaming = true),
            streaming = true,
            error = null,
            lastEventId = null
        )
        viewModelScope.launch {
            val requestId = runCatching { repository.send(conversationId, trimmed) }.getOrElse { error ->
                fail("发送失败：${error.message}")
                return@launch
            }
            consume(requestId, fromEventId = null, allowRetry = true)
        }
    }

    private suspend fun consume(requestId: String, fromEventId: Long?, allowRetry: Boolean) {
        try {
            repository.stream(requestId, fromEventId).collect { frame ->
                frame.eventId?.let { id ->
                    mutableState.value = mutableState.value.copy(lastEventId = id)
                }
                apply(frame.event)
            }
        } catch (error: Throwable) {
            val lastEventId = mutableState.value.lastEventId
            if (allowRetry && lastEventId != null) {
                consume(requestId, lastEventId, allowRetry = false)
                return
            }
            fail("连接中断：${error.message}")
        }
    }

    private fun apply(event: ChatEvent) {
        when (event) {
            is ChatEvent.Text -> appendDelta(event.delta)
            is ChatEvent.ToolCall -> appendDelta("\n[tool] ${event.name}\n")
            is ChatEvent.ToolResult -> appendDelta("\n[${event.name}] ${event.output}\n")
            is ChatEvent.FileRef -> appendDelta("\n[file] ${event.path}\n")
            is ChatEvent.Error -> {
                finishStreaming()
                mutableState.value = mutableState.value.copy(error = event.message)
            }
            is ChatEvent.Done -> {
                finishStreaming()
                if (event.finishReason !in ChatEvent.ALLOWED_FINISH_REASONS) {
                    mutableState.value = mutableState.value.copy(
                        error = "非预期的 finishReason：${event.finishReason}"
                    )
                }
            }
        }
    }

    private fun appendDelta(delta: String) {
        val messages = mutableState.value.messages.toMutableList()
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index < 0) return
        messages[index] = messages[index].copy(text = messages[index].text + delta)
        mutableState.value = mutableState.value.copy(messages = messages)
    }

    private fun finishStreaming() {
        val messages = mutableState.value.messages.map {
            if (it.streaming) it.copy(streaming = false) else it
        }
        mutableState.value = mutableState.value.copy(messages = messages, streaming = false)
    }

    private fun fail(message: String) {
        finishStreaming()
        mutableState.value = mutableState.value.copy(error = message)
    }
}