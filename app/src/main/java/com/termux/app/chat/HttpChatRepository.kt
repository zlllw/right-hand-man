package com.termux.app.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 契约 B 的 B3 的 HTTP/SSE 实现。
 *
 * 断线续传依赖 `?from=<lastEventId>`（契约 B 的 B5）：由调用方把上次收到的最后一个
 * 事件 id 传进来，这里只负责拼参数，不做任何"去重"启发式。
 */
class HttpChatRepository(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient = defaultClient()
) : ChatRepository {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("health").build())
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string()?.contains("\"ok\"") == true
            }
        }.getOrDefault(false)
    }

    override suspend fun send(conversationId: String, message: String): String =
        withContext(Dispatchers.IO) {
            val payload = buildString {
                append("{\"message\":").append(quote(message))
                append(",\"conversationId\":").append(quote(conversationId))
                append('}')
            }
            val request = Request.Builder()
                .url(baseUrl.newBuilder().addPathSegments("chat").build())
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("POST /chat 失败：HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val requestId = Json.string(Json.parseObject(body), "requestId")
                if (requestId.isEmpty()) throw IOException("POST /chat 未返回 requestId")
                requestId
            }
        }

    override fun stream(requestId: String, fromEventId: Long?): Flow<ChatFrame> = callbackFlow {
        val url = baseUrl.newBuilder()
            .addPathSegments("chat")
            .addPathSegment(requestId)
            .addPathSegment("stream")
            .apply { if (fromEventId != null) addQueryParameter("from", fromEventId.toString()) }
            .build()
        val request = Request.Builder().url(url).get().build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val event = ChatEvent.decode(type ?: ChatEvent.TEXT, data)
                    trySend(ChatFrame(id?.toLongOrNull(), event))
                } catch (error: IllegalArgumentException) {
                    // 白名单外的事件、或载荷不是合法 JSON：显式失败，不静默丢弃
                    close(error)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("SSE 连接失败：HTTP ${response?.code}"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    companion object {
        /** SSE 是长连接：读超时必须关掉，否则空闲时会被 OkHttp 掐断。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}