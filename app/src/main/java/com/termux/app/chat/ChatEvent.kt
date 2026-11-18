package com.termux.app.chat

/**
 * 契约 B 的 B4：Bridge 推给 App 的事件集合。
 *
 * 该模型是**纯 Kotlin**，不依赖 Compose 也不依赖 Android（契约 B 的 7.2、契约 C 的 C0），
 * 因此既能在 JVM 单测里构造，也能被将来的 WebView 分支原样复用。
 */
sealed class ChatEvent {

    data class Text(val delta: String) : ChatEvent()

    data class ToolCall(val name: String, val args: Map<String, Any?>) : ChatEvent()

    data class ToolResult(val name: String, val output: String) : ChatEvent()

    data class FileRef(val path: String, val mime: String, val size: Long, val url: String) : ChatEvent()

    data class Error(val message: String) : ChatEvent()

    data class Done(val finishReason: String) : ChatEvent()

    companion object {
        const val TEXT = "text"
        const val TOOL_CALL = "tool_call"
        const val TOOL_RESULT = "tool_result"
        const val FILE = "file"
        const val ERROR = "error"
        const val DONE = "done"

        /** 契约 B 的 B4 白名单。出现名单外的事件要显式失败，而不是静默忽略。 */
        val ALLOWED_NAMES: Set<String> = setOf(TEXT, TOOL_CALL, TOOL_RESULT, FILE, ERROR, DONE)

        /** 契约 B 的 B4：finishReason 当前只允许 stop。 */
        val ALLOWED_FINISH_REASONS: Set<String> = setOf("stop")

        /**
         * 把一帧 SSE 的 `event` 名与 `data` 载荷解码成模型。
         *
         * @throws IllegalArgumentException 事件名在 B4 白名单之外，或载荷不是合法 JSON 对象。
         */
        fun decode(name: String, data: String): ChatEvent {
            val payload: Map<String, Any?> =
                if (data.isBlank()) emptyMap() else Json.parseObject(data)
            return when (name) {
                TEXT -> Text(Json.string(payload, "delta"))
                TOOL_CALL -> ToolCall(Json.string(payload, "name"), Json.map(payload, "args"))
                TOOL_RESULT -> ToolResult(Json.string(payload, "name"), Json.string(payload, "output"))
                FILE -> FileRef(
                    path = Json.string(payload, "path"),
                    mime = Json.string(payload, "mime"),
                    size = Json.long(payload, "size"),
                    url = Json.string(payload, "url")
                )
                ERROR -> Error(Json.string(payload, "message"))
                DONE -> Done(Json.string(payload, "finishReason", "stop"))
                else -> throw IllegalArgumentException("事件 '$name' 不在契约 B 的 B4 白名单内")
            }
        }
    }
}