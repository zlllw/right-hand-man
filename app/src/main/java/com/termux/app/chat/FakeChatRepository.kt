package com.termux.app.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * 测试与 UI Preview 用的假实现。
 *
 * 契约 C 的 C3 要求：同一套 `ChatRepositoryContractTest` 必须同时跑在本实现与
 * `HttpChatRepository` 上，避免 Fake 随实现漂移。
 */
class FakeChatRepository(
    private val script: List<ChatEvent> = DEFAULT_SCRIPT,
    private val failAfterFrames: Int? = null
) : ChatRepository {

    var lastSent: Pair<String, String>? = null
        private set

    /** 记录 stream 调用，便于断言"重连时带上了 from"（契约 B 的 B5）。 */
    val streamCalls: MutableList<Pair<String, Long?>> = mutableListOf()

    override suspend fun health(): Boolean = true

    override suspend fun send(conversationId: String, message: String): String {
        lastSent = conversationId to message
        return "fake-" + conversationId
    }

    /** 第几次 stream 调用；断线注入只作用于第一次，好让"重连后成功"的场景能被测到。 */
    private var streamCallCount = 0

    override fun stream(requestId: String, fromEventId: Long?): Flow<ChatFrame> = flow {
        streamCalls += requestId to fromEventId
        val callIndex = ++streamCallCount
        script.forEachIndexed { index, event ->
            val eventId = index + 1L
            // 契约 B 的 B5：from 的语义是「不含该 id」，这里必须一致
            if (fromEventId != null && eventId <= fromEventId) return@forEachIndexed
            if (failAfterFrames != null && callIndex == 1 && eventId > failAfterFrames) {
                throw IOException("fake 断线")
            }
            emit(ChatFrame(eventId, substitute(event)))
        }
    }

    private fun substitute(event: ChatEvent): ChatEvent {
        val message = lastSent?.second ?: ""
        return when (event) {
            is ChatEvent.Text -> ChatEvent.Text(event.delta.replace("{message}", message))
            else -> event
        }
    }

    companion object {
        val DEFAULT_SCRIPT: List<ChatEvent> = listOf(
            ChatEvent.Text("echo: "),
            ChatEvent.Text("{message}"),
            ChatEvent.Done("stop")
        )
    }
}