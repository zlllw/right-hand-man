package com.termux.app.chat

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 *
 * 流与会话绑定：每个 conversationId 最多一个进行中的请求，切到别的会话时
 * 旧流在后台继续累积，不会被写成新会话的消息；Bridge 侧也按 conversationId
 * 串行、不同会话可并发。
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
        val connected: Boolean = false,
        val conversationId: String = "default",
        val conversations: List<ConversationSummary> = emptyList(),
        val pendingAttachments: List<UploadedFile> = emptyList(),
        val uploading: Boolean = false,
        val workspaceFiles: List<WorkspaceFile> = emptyList(),
        val preview: WorkspacePreview? = null
    )

    private data class ActiveStream(
        val conversationId: String,
        var requestId: String? = null,
        var lastEventId: Long? = null,
        var job: Job? = null
    )

    private val mutableState = MutableStateFlow(UiState(conversationId = conversationId))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private var conversationId: String = conversationId
    private val cache = mutableMapOf<String, List<Message>>()
    private val active = mutableMapOf<String, ActiveStream>()

    /**
     * Bridge 就绪后从 Fake 切到真实 HTTP（任务书阶段 1c）。
     *
     * 切换时终止所有流并清空本地缓存：旧消息是在另一个 Repository 上产生的，
     * 保留会让事件编号与真实 Bridge 串味。
     */
    fun useRepository(newRepository: ChatRepository) {
        active.values.forEach { it.job?.cancel() }
        active.clear()
        cache.clear()
        repository = newRepository
        mutableState.value = UiState(conversationId = conversationId)
    }

    fun refreshHealth() {
        viewModelScope.launch {
            val healthy = runCatching { repository.health() }.getOrDefault(false)
            updateUi { it.copy(connected = healthy) }
        }
    }

    /** 拉取历史会话列表（左上角抽屉打开时调用）。 */
    fun loadConversations() {
        viewModelScope.launch {
            val conversations = runCatching { repository.listConversations() }.getOrDefault(emptyList())
            updateUi { it.copy(conversations = conversations) }
        }
    }

    /** 新建对话：换一个新的 conversationId；旧会话的生成继续在后台跑。 */
    fun newConversation() {
        conversationId = "c-" + java.util.UUID.randomUUID().toString().take(8)
        cache[conversationId] = emptyList()
        mutableState.value = UiState(
            conversationId = conversationId,
            streaming = active.containsKey(conversationId),
            conversations = mutableState.value.conversations,
            connected = mutableState.value.connected
        )
    }

    /**
     * 切换到历史会话。生成中的会话直接展示本地实时缓存，不再拉历史；
     * 其余会话先展示缓存、再用后端历史校准。
     */
    fun openConversation(targetId: String) {
        if (targetId == conversationId) return
        val live = active.containsKey(targetId)
        switchTo(targetId, cache[targetId] ?: emptyList())
        if (live) return

        viewModelScope.launch {
            val history = runCatching { repository.conversationMessages(targetId) }.getOrDefault(emptyList())
            if (conversationId != targetId || active.containsKey(targetId)) return@launch
            val messages = history.map {
                Message(
                    role = if (it.role == "user") Role.USER else Role.ASSISTANT,
                    text = it.text
                )
            }
            setMessages(targetId, messages)
        }
    }

    /** 重命名会话：先写后端，成功后刷新本地列表。 */
    fun renameConversation(targetId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.renameConversation(targetId, trimmed) }
                .onSuccess {
                    updateUi { state ->
                        state.copy(
                            conversations = state.conversations.map {
                                if (it.conversationId == targetId) it.copy(title = trimmed) else it
                            }
                        )
                    }
                }
                .onFailure { error ->
                    updateUi { it.copy(error = "重命名失败：${error.message}") }
                }
        }
    }

    /** 删除会话：先掐掉该会话的流，再删后端；删的是当前会话就开一段新对话。 */
    fun deleteConversation(targetId: String) {
        active.remove(targetId)?.let { stream ->
            stream.job?.cancel()
            stream.requestId?.let { requestId ->
                viewModelScope.launch { runCatching { repository.abort(requestId) } }
            }
        }
        viewModelScope.launch {
            runCatching { repository.deleteConversation(targetId) }
                .onSuccess {
                    cache.remove(targetId)
                    val remaining = mutableState.value.conversations.filterNot {
                        it.conversationId == targetId
                    }
                    if (conversationId == targetId) {
                        conversationId = "c-" + java.util.UUID.randomUUID().toString().take(8)
                        cache[conversationId] = emptyList()
                        mutableState.value = UiState(
                            conversationId = conversationId,
                            streaming = active.containsKey(conversationId),
                            conversations = remaining,
                            connected = mutableState.value.connected
                        )
                    } else {
                        updateUi { it.copy(conversations = remaining) }
                    }
                }
                .onFailure { error ->
                    updateUi { it.copy(error = "删除失败：${error.message}") }
                }
        }
    }

    /** 上传附件到 rhmdir（Bridge 负责落盘与重名改名）。 */
    fun attach(filename: String, bytes: ByteArray) {
        if (mutableState.value.uploading) return
        updateUi { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { repository.uploadFile(filename, bytes) }
            val current = mutableState.value
            result.onSuccess { uploaded ->
                mutableState.value = current.copy(
                    uploading = false,
                    pendingAttachments = current.pendingAttachments + uploaded,
                    streaming = active.containsKey(conversationId)
                )
                loadFiles()
            }.onFailure { error ->
                mutableState.value = current.copy(
                    uploading = false,
                    error = "上传失败：${error.message}",
                    streaming = active.containsKey(conversationId)
                )
            }
        }
    }

    fun removeAttachment(index: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(
            pendingAttachments = current.pendingAttachments.filterIndexed { i, _ -> i != index },
            streaming = active.containsKey(conversationId)
        )
    }

    /** 拉取 ~/rhmdir 文件列表（打开工作区面板时调用）。 */
    fun loadFiles() {
        viewModelScope.launch {
            val files = runCatching { repository.listFiles() }.getOrDefault(emptyList())
            updateUi { it.copy(workspaceFiles = files) }
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
            updateUi { it.copy(preview = WorkspacePreview(name, size, text)) }
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
                updateUi { it.copy(error = "下载失败：${error.message}") }
            }
        }
    }

    /** 删除工作区文件并刷新列表。 */
    fun deleteFile(name: String) {
        viewModelScope.launch {
            runCatching { repository.deleteFile(name) }
                .onSuccess { loadFiles() }
                .onFailure { error ->
                    updateUi { it.copy(error = "删除失败：${error.message}") }
                }
        }
    }

    fun closePreview() {
        updateUi { it.copy(preview = null) }
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
            ),
            streaming = active.containsKey(conversationId)
        )
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = mutableState.value.pendingAttachments
        val targetId = conversationId
        if ((trimmed.isEmpty() && attachments.isEmpty()) || active.containsKey(targetId)) return
        val outgoing = if (attachments.isEmpty()) {
            trimmed
        } else {
            buildString {
                append(trimmed)
                append("\n\n附件（已放入 ~/rhmdir，可直接读取）：")
                attachments.forEach { append("\n- ").append(it.path) }
            }
        }

        val stream = ActiveStream(conversationId = targetId)
        active[targetId] = stream
        val messages = messagesOf(targetId) +
            Message(Role.USER, outgoing) +
            Message(Role.ASSISTANT, "", streaming = true)
        setMessages(targetId, messages)
        mutableState.value = mutableState.value.copy(
            error = null,
            pendingAttachments = emptyList(),
            streaming = active.containsKey(conversationId)
        )

        stream.job = viewModelScope.launch {
            val requestId = try {
                repository.send(targetId, outgoing)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(targetId, "发送失败：${error.message}")
                return@launch
            }
            if (active[targetId] !== stream) return@launch
            stream.requestId = requestId
            consume(targetId, stream, fromEventId = null, allowRetry = true)
        }
    }

    /** 终止当前会话的生成；别的会话里跑着的流不受影响。 */
    fun stop() {
        val targetId = conversationId
        val stream = active[targetId] ?: return
        stream.job?.cancel()
        active.remove(targetId)
        stream.requestId?.let { requestId ->
            viewModelScope.launch { runCatching { repository.abort(requestId) } }
        }
        val messages = messagesOf(targetId).toMutableList()
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index >= 0) {
            val base = messages[index].text
            messages[index] = messages[index].copy(
                text = if (base.isBlank()) "已停止。" else base + "\n\n已停止。",
                streaming = false,
                tools = messages[index].tools.map { if (it.running) it.copy(running = false) else it }
            )
        }
        setMessages(targetId, messages)
    }

    private suspend fun consume(
        targetId: String,
        stream: ActiveStream,
        fromEventId: Long?,
        allowRetry: Boolean
    ) {
        val requestId = stream.requestId ?: return
        try {
            repository.stream(requestId, fromEventId).collect { frame ->
                if (active[targetId] !== stream) return@collect
                frame.eventId?.let { stream.lastEventId = it }
                apply(targetId, frame.event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (active[targetId] !== stream) return
            val lastEventId = stream.lastEventId
            if (allowRetry && lastEventId != null) {
                consume(targetId, stream, lastEventId, allowRetry = false)
                return
            }
            fail(targetId, "连接中断：${error.message}")
        }
    }

    private fun apply(targetId: String, event: ChatEvent) {
        when (event) {
            is ChatEvent.Text -> appendDelta(targetId, event.delta)
            is ChatEvent.ToolCall -> appendDelta(targetId, "\n[tool] ${event.name}\n")
            is ChatEvent.ToolResult -> appendDelta(targetId, "\n[${event.name}] ${event.output}\n")
            is ChatEvent.FileRef -> appendDelta(targetId, "\n[file] ${event.path}\n")
            is ChatEvent.Error -> {
                fail(targetId, event.message)
                loadConversations()
            }
            is ChatEvent.Done -> {
                finishStreaming(targetId)
                if (event.finishReason !in ChatEvent.ALLOWED_FINISH_REASONS &&
                    targetId == conversationId
                ) {
                    updateUi { it.copy(error = "非预期的 finishReason：${event.finishReason}") }
                }
                loadConversations()
            }
        }
    }

    private fun appendToolCall(targetId: String, name: String) {
        updateLastAssistant(targetId) { message ->
            message.copy(tools = message.tools + ToolEntry(name = name))
        }
    }

    private fun appendToolResult(targetId: String, name: String, output: String) {
        updateLastAssistant(targetId) { message ->
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

    private fun updateLastAssistant(targetId: String, transform: (Message) -> Message) {
        val messages = messagesOf(targetId).toMutableList()
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index < 0) return
        messages[index] = transform(messages[index])
        setMessages(targetId, messages)
    }

    private fun appendDelta(targetId: String, delta: String) {
        val messages = messagesOf(targetId).toMutableList()
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index < 0) return
        messages[index] = messages[index].copy(text = messages[index].text + delta)
        setMessages(targetId, messages)
    }

    private fun finishStreaming(targetId: String) {
        active.remove(targetId)
        val messages = messagesOf(targetId).map {
            if (it.streaming) it.copy(streaming = false) else it
        }
        setMessages(targetId, messages)
    }

    private fun fail(targetId: String, message: String) {
        active.remove(targetId)
        var messages = messagesOf(targetId).map {
            if (it.streaming) it.copy(streaming = false) else it
        }
        if (targetId != conversationId) {
            val index = messages.indexOfLast { it.role == Role.ASSISTANT }
            if (index >= 0) {
                val base = messages[index].text
                messages = messages.toMutableList().also {
                    it[index] = it[index].copy(
                        text = if (base.isBlank()) "[错误] $message" else "$base\n\n[错误] $message"
                    )
                }
            }
        }
        setMessages(targetId, messages)
        if (targetId == conversationId) {
            mutableState.value = mutableState.value.copy(
                error = message,
                streaming = active.containsKey(conversationId)
            )
        }
    }

    private fun messagesOf(targetId: String): List<Message> =
        cache[targetId] ?: if (targetId == conversationId) mutableState.value.messages else emptyList()

    private fun setMessages(targetId: String, messages: List<Message>) {
        cache[targetId] = messages
        if (targetId == conversationId) {
            mutableState.value = mutableState.value.copy(
                messages = messages,
                streaming = active.containsKey(targetId)
            )
        }
    }

    private fun switchTo(targetId: String, messages: List<Message>) {
        conversationId = targetId
        cache[targetId] = messages
        mutableState.value = UiState(
            messages = messages,
            streaming = active.containsKey(targetId),
            conversationId = targetId,
            conversations = mutableState.value.conversations,
            connected = mutableState.value.connected
        )
    }

    private fun updateUi(transform: (UiState) -> UiState) {
        mutableState.value = transform(mutableState.value).copy(
            streaming = active.containsKey(conversationId)
        )
    }
}