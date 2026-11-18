package com.termux.app.chat

import kotlinx.coroutines.flow.Flow

/**
 * 一帧 SSE 事件。
 *
 * `eventId` 允许为 null：契约 B 的 B5 规定，超过 60 秒保留窗口的重连只补一条
 * 不带环形缓冲 id 的 `done`。
 */
data class ChatFrame(val eventId: Long?, val event: ChatEvent)

/**
 * 契约 B 的 B3 在 App 侧的映射。
 *
 * **约束**：本接口及其实现不得出现任何 Compose / Android UI 类型
 * （任务书 7.2、契约 B 的 7.2），否则将来替换成 WebView 分支就无法复用。
 */
interface ChatRepository {

    /** `GET /health`。Bridge 自身健康即返回 true，与上游 Agent 状态无关。 */
    suspend fun health(): Boolean

    /** `POST /chat`，返回 `requestId`。 */
    suspend fun send(conversationId: String, message: String): String

    /**
     * `GET /chat/{requestId}/stream`。
     *
     * @param fromEventId 传上次收到的最后一个 id，走 `?from=` 续传，不重放（契约 B 的 B5）。
     */
    fun stream(requestId: String, fromEventId: Long? = null): Flow<ChatFrame>
}