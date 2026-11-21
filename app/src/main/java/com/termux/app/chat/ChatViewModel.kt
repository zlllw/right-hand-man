package com.termux.app.chat

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

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
    private var repository: ChatRepository,
    conversationId: String = "default"
) : ViewModel() {

    enum class Role { USER, ASSISTANT }

    data class ToolEntry(
        val name: String,
        val output: String? = null,
        val running: Boolean = true
    )

    data class Message(
        val role: Role,
        val text: String,
        val streaming: Boolean = false,
        val tools: List<ToolEntry> = emptyList()
    )

    data class WorkspacePreview(val name: String, val size: Long, val text: String?)

    data class UiState(
        val messages: List<Message> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
        val lastEventId: Long? = null,
        val connected: Boolean = false,
        val conversationId: String = "default",
        val conversations: List<ConversationSummary> = emptyList(),
        val pendingAttachments: List<UploadedFile> = emptyList(),
        val uploading: Boolean = false,
        val workspaceFiles: List<WorkspaceFile> = emptyList(),
        val preview: WorkspacePreview? = null
    )

    private val mutableState = MutableStateFlow(UiState(conversationId = conversationId))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var conversationId: String = conversationId
    private var currentRequestId: String? = null
    private var streamJob: kotlinx.coroutines.Job? = null

    /**
     * Bridge 就绪后从 Fake 切到真实 HTTP（任务书阶段 1c）。
     *
     * 切换时清空对话状态：旧消息是在另一个 Repository 上产生的，保留会让
     * `lastEventId` 与真实 Bridge 的事件编号串味。
     */
    fun useRepository(newRepository: ChatRepository) {
        repository = newRepository
        mutableState.value = UiState(conversationId = conversationId)
    }

    fun refreshHealth() {
        viewModelScope.launch {
            val healthy = runCatching { repository.health() }.getOrDefault(false)
            mutableState.value = mutableState.value.copy(connected = healthy)
        }
    }

    /** 拉取历史会话列表（左上角抽屉打开时调用）。 */
    fun loadConversations() {
        viewModelScope.launch {
            val conversations = runCatching { repository.listConversations() }.getOrDefault(emptyList())
            mutableState.value = mutableState.value.copy(conversations = conversations)
        }
    }

    /** 新建对话：换一个新的 conversationId，清空当前消息与附件。 */
    fun newConversation() {
        if (mutableState.value.streaming) return
        conversationId = "c-" + java.util.UUID.randomUUID().toString().take(8)
        mutableState.value = UiState(
            conversationId = conversationId,
            conversations = mutableState.value.conversations,
            connected = mutableState.value.connected
        )
    }

    /** 切换到历史会话：先把已归一化的历史灌进消息列表。 */
    fun openConversation(targetId: String) {
        if (mutableState.value.streaming) return
        conversationId = targetId
        mutableState.value = UiState(
            conversationId = targetId,
            conversations = mutableState.value.conversations,
            connected = mutableState.value.connected
        )
        viewModelScope.launch {
            val history = runCatching { repository.conversationMessages(targetId) }.getOrDefault(emptyList())
            if (conversationId != targetId) return@launch
            val messages = history.map {
                Message(
                    role = if (it.role == "user") Role.USER else Role.ASSISTANT,
                    text = it.text
                )
            }
            mutableState.value = mutableState.value.copy(messages = messages)
        }
    }

    /** 重命名会话：先写后端，成功后刷新本地列表。 */
    fun renameConversation(targetId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.renameConversation(targetId, trimmed) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        conversations = mutableState.value.conversations.map {
                            if (it.conversationId == targetId) it.copy(title = trimmed) else it
                        }
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(error = "重命名失败：${error.message}")
                }
        }
    }

    /** 删除会话：后端删除成功后刷新列表；删的是当前会话就开一段新对话。 */
    fun deleteConversation(targetId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteConversation(targetId) }
                .onSuccess {
                    val remaining = mutableState.value.conversations.filterNot {
                        it.conversationId == targetId
                    }
                    if (conversationId == targetId) {
                        conversationId = "c-" + java.util.UUID.randomUUID().toString().take(8)
                        mutableState.value = UiState(
                            conversationId = conversationId,
                            conversations = remaining,
                            connected = mutableState.value.connected
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(conversations = remaining)
                    }
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(error = "删除失败：${error.message}")
                }
        }
    }

    /** 上传附件到 rhmdir（Bridge 负责落盘与重名改名）。 */
    fun attach(filename: String, bytes: ByteArray) {
        if (mutableState.value.uploading) return
        mutableState.value = mutableState.value.copy(uploading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { repository.uploadFile(filename, bytes) }
            val current = mutableState.value
            result.onSuccess { uploaded ->
                mutableState.value = current.copy(
                    uploading = false,
                    pendingAttachments = current.pendingAttachments + uploaded
                )
                loadFiles()
            }.onFailure { error ->
                mutableState.value = current.copy(uploading = false, error = "上传失败：${error.message}")
            }
        }
    }

    fun removeAttachment(index: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(
            pendingAttachments = current.pendingAttachments.filterIndexed { i, _ -> i != index }
        )
    }

    /** 拉取 ~/rhmdir 文件列表（打开工作区面板时调用）。 */
    fun loadFiles() {
        viewModelScope.launch {
            val files = runCatching { repository.listFiles() }.getOrDefault(emptyList())
            mutableState.value = mutableState.value.copy(workspaceFiles = files)
        }
    }

    /** 预览工作区文件：文本（≤256KB 且能按 UTF-8 解码）才给内容，其余提示可引用。 */
    fun previewFile(name: String) {
        viewModelScope.launch {
            val bytes = runCatching { repository.downloadFile(name) }.getOrNull()
            val size = bytes?.size?.toLong() ?: 0L
            val text = bytes?.takeIf { it.size <= 256 * 1024 }?.let {
                val decoded = String(it, Charsets.UTF_8)
                decoded.takeIf { value -> value.none { ch -> ch == '\uFFFD' } }
            }
            mutableState.value = mutableState.value.copy(preview = WorkspacePreview(name, size, text))
        }
    }

    /** 下载工作区文件到系统「下载/rhmdir」。 */
    fun saveToDownloads(context: Context, name: String) {
        viewModelScope.launch {
            runCatching {
                val bytes = repository.downloadFile(name)
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/rhmdir")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建下载条目")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("无法写入下载文件")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(error = "下载失败：${error.message}")
            }
        }
    }

    /** 删除工作区文件并刷新列表。 */
    fun deleteFile(name: String) {
        viewModelScope.launch {
            runCatching { repository.deleteFile(name) }
                .onSuccess { loadFiles() }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(error = "删除失败：${error.message}")
                }
        }
    }

    fun closePreview() {
        mutableState.value = mutableState.value.copy(preview = null)
    }

    /** 引用 workdir 里已有文件：不重复上传，直接加入待发附件。 */
    fun attachExisting(file: WorkspaceFile) {
        val current = mutableState.value
        if (current.pendingAttachments.any { it.name == file.name }) return
        mutableState.value = current.copy(
            pendingAttachments = current.pendingAttachments + UploadedFile(
                name = file.name,
                path = "~/rhmdir/" + file.name,
                size = file.size
            )
        )
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = mutableState.value.pendingAttachments
        if ((trimmed.isEmpty() && attachments.isEmpty()) || mutableState.value.streaming) return
        val outgoing = if (attachments.isEmpty()) {
            trimmed
        } else {
            buildString {
                append(trimmed)
                append("\n\n附件（已放入 ~/rhmdir，可直接读取）：")
                attachments.forEach { append("\n- ").append(it.path) }
            }
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            messages = current.messages +
                Message(Role.USER, outgoing) +
                Message(Role.ASSISTANT, "", streaming = true),
            streaming = true,
            error = null,
            lastEventId = null,
            pendingAttachments = emptyList()
        )
        streamJob = viewModelScope.launch {
            val requestId = runCatching { repository.send(conversationId, outgoing) }.getOrElse { error ->
                fail("发送失败：${error.message}")
                return@launch
            }
            currentRequestId = requestId
            consume(requestId, fromEventId = null, allowRetry = true)
        }
    }

    /** 终止当前生成：先给 SSE 收尾（UI 立即停），再尽力通知上游 abort。 */
    fun stop() {
        if (!mutableState.value.streaming) return
        val requestId = currentRequestId
        streamJob?.cancel()
        streamJob = null
        if (requestId != null) {
            viewModelScope.launch { runCatching { repository.abort(requestId) } }
        }
        val messages = mutableState.value.messages.toMutableList()
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index >= 0) {
            val base = messages[index].text
            messages[index] = messages[index].copy(
                text = if (base.isBlank()) "已停止。" else base + "\n\n已停止。",
                streaming = false,
                tools = messages[index].tools.map { if (it.running) it.copy(running = false) else it }
            )
        }
        mutableState.value = mutableState.value.copy(messages = messages, streaming = false)
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

    private fun appendToolCall(name: String) {
        updateLastAssistant { message ->
            message.copy(tools = message.tools + ToolEntry(name = name))
        }
    }

    private fun appendToolResult(name: String, output: String) {
        updateLastAssistant { message ->
            val tools = message.tools.toMutableList()
            val index = tools.indexOfLast { it.name == name && it.running }
            if (index >= 0) {
                tools[index] = tools[index].copy(output = output, running = false)
            } else {
                tools.add(ToolEntry(name = name, output = output, running = false))
            }
            message.copy(tools = tools)
        }
    }

    private fun updateLastAssistant(transform: (Message) -> Message) {
        val messages = mutableState.value.messages.toMutableList()
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index < 0) return
        messages[index] = transform(messages[index])
        mutableState.value = mutableState.value.copy(messages = messages)
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