package com.termux.app.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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

    override suspend fun listConversations(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("conversations").build())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET /conversations 失败：HTTP ${response.code}")
            Json.mapArray(Json.parseArray(response.body?.string().orEmpty())).map { item ->
                ConversationSummary(
                    conversationId = Json.string(item, "conversationId"),
                    title = Json.string(item, "title"),
                    updatedAt = Json.long(item, "updatedAt")
                )
            }
        }
    }

    override suspend fun conversationMessages(conversationId: String, limit: Int): List<HistoryMessage> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(
                    baseUrl.newBuilder()
                        .addPathSegments("conversations")
                        .addPathSegment(conversationId)
                        .addPathSegment("messages")
                        .addQueryParameter("limit", limit.toString())
                        .build()
                )
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GET messages 失败：HTTP ${response.code}")
                Json.mapArray(Json.parseArray(response.body?.string().orEmpty())).map { item ->
                    HistoryMessage(
                        role = Json.string(item, "role"),
                        text = Json.string(item, "text"),
                        ts = (item["ts"] as? Double)?.toLong() ?: 0L
                    )
                }
            }
        }

    override suspend fun renameConversation(conversationId: String, title: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(
                    baseUrl.newBuilder()
                        .addPathSegments("conversations")
                        .addPathSegment(conversationId)
                        .build()
                )
                .patch("{\"title\":${quote(title)}}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("PATCH /conversations 失败：HTTP ${response.code}")
                }
            }
        }

    override suspend fun deleteConversation(conversationId: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(
                    baseUrl.newBuilder()
                        .addPathSegments("conversations")
                        .addPathSegment(conversationId)
                        .build()
                )
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("DELETE /conversations 失败：HTTP ${response.code}")
                }
            }
        }

    override suspend fun abort(requestId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("abort").build())
            .post("{\"requestId\":${quote(requestId)}}".toRequestBody(jsonMediaType))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }
        Unit
    }

    override suspend fun downloadFile(name: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("files").addPathSegment(name).build())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET /files/{name} 失败：HTTP ${response.code}")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun deleteFile(name: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("files").addPathSegment(name).build())
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DELETE /files/{name} 失败：HTTP ${response.code}")
        }
    }

    override suspend fun listFiles(): List<WorkspaceFile> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("files").build())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET /files 失败：HTTP ${response.code}")
            val payload = Json.parseObject(response.body?.string().orEmpty())
            Json.maps(payload, "files").map { item ->
                WorkspaceFile(
                    name = Json.string(item, "name"),
                    size = Json.long(item, "size"),
                    mime = Json.string(item, "mime", "application/octet-stream")
                )
            }
        }
    }

    override suspend fun uploadFile(filename: String, bytes: ByteArray): UploadedFile =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    filename,
                    bytes.toRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url(baseUrl.newBuilder().addPathSegments("files").build())
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("上传失败：HTTP ${response.code} $text")
                }
                val payload = Json.parseObject(text)
                UploadedFile(
                    name = Json.string(payload, "name"),
                    path = Json.string(payload, "path"),
                    size = Json.long(payload, "size")
                )
            }
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