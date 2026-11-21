package com.termux.app.chat

import kotlinx.coroutines.flow.Flow

/**
 * 一帧 SSE 事件。
 *
 * `eventId` 允许为 null：契约 B 的 B5 规定，超过 60 秒保留窗口的重连只补一条
 * 不带环形缓冲 id 的 `done`。
 */
data class ChatFrame(val eventId: Long?, val event: ChatEvent)

/** B9 扩展：`GET /conversations` 的一行。 */
data class ConversationSummary(
    val conversationId: String,
    val title: String,
    val updatedAt: Long
)

/** B9 扩展：`GET /conversations/{id}/messages` 归一化后的一条历史消息。 */
data class HistoryMessage(
    val role: String,
    val text: String,
    val ts: Long
)

/** `GET /files` 的一行（rhmdir 工作区）。 */
data class WorkspaceFile(
    val name: String,
    val size: Long,
    val mime: String
)

/** `POST /files` 的响应。path 是 Bridge 侧展示路径（~/rhmdir/xxx）。 */
data class UploadedFile(
    val name: String,
    val path: String,
    val size: Long
)

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

    /** `GET /conversations`（B9 扩展）。不支持的实现返回空列表。 */
    suspend fun listConversations(): List<ConversationSummary> = emptyList()

    /** `GET /conversations/{id}/messages?limit=N`（B9 扩展）。 */
    suspend fun conversationMessages(conversationId: String, limit: Int = 200): List<HistoryMessage> = emptyList()

    /** `PATCH /conversations/{id}`：重命名会话（B9 扩展）。 */
    suspend fun renameConversation(conversationId: String, title: String) {}

    /** `DELETE /conversations/{id}`：删除会话（B9 扩展）。 */
    suspend fun deleteConversation(conversationId: String) {}

    /** `POST /abort`：中断一次生成（尽力而为）。 */
    suspend fun abort(requestId: String) {}

    /** `GET /files`：rhmdir 工作区文件列表。 */
    suspend fun listFiles(): List<WorkspaceFile> = emptyList()

    /** `POST /files`：multipart 上传到 rhmdir，重名由 Bridge 自动改名。 */
    suspend fun uploadFile(filename: String, bytes: ByteArray): UploadedFile =
        throw java.io.IOException("当前 Repository 不支持文件上传")

    /** `GET /files/{name}`：读取工作区文件内容（预览/下载）。 */
    suspend fun downloadFile(name: String): ByteArray =
        throw java.io.IOException("当前 Repository 不支持文件下载")

    /** `DELETE /files/{name}`：删除工作区文件。 */
    suspend fun deleteFile(name: String) {
        throw java.io.IOException("当前 Repository 不支持删除文件")
    }
}