package com.termux.app.chat

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 C 的 C3：同一套契约测试必须同时跑在 [FakeChatRepository] 与
 * [HttpChatRepository] 上，避免 Fake 随真实实现漂移。
 */
abstract class ChatRepositoryContractTest {

    abstract fun createRepository(): ChatRepository

    @Test
    fun `health 返回 true`() = runBlocking {
        assertTrue(createRepository().health())
    }

    @Test
    fun `发送后按序收到 text 与 done`() = runBlocking {
        val repository = createRepository()
        val requestId = repository.send("c1", "hello")
        assertTrue(requestId.isNotEmpty())

        val frames = repository.stream(requestId).toList()
        assertEquals(3, frames.size)
        assertEquals(listOf(1L, 2L, 3L), frames.map { it.eventId })
        assertTrue(frames[0].event is ChatEvent.Text)
        assertTrue(frames[1].event is ChatEvent.Text)
        assertTrue(frames[2].event is ChatEvent.Done)

        val text = frames.filter { it.event is ChatEvent.Text }
            .joinToString("") { (it.event as ChatEvent.Text).delta }
        assertTrue("实际文本：$text", text.contains("hello"))
        assertEquals("stop", (frames[2].event as ChatEvent.Done).finishReason)
    }

    @Test
    fun `from 续传不重放已收事件`() = runBlocking {
        val repository = createRepository()
        val requestId = repository.send("c2", "hello")
        repository.stream(requestId).toList()

        val replay = repository.stream(requestId, fromEventId = 2).toList()
        assertEquals(1, replay.size)
        assertEquals(3L, replay.first().eventId)
        assertTrue(replay.first().event is ChatEvent.Done)
    }
}

/** Fake 实现：直接跑，不碰网络。 */
class FakeChatRepositoryContractTest : ChatRepositoryContractTest() {
    override fun createRepository(): ChatRepository = FakeChatRepository()
}

/** 真实实现：MockWebServer 扮演 Bridge（契约 C 的 C1，L2）。 */
class HttpChatRepositoryContractTest : ChatRepositoryContractTest() {

    private lateinit var server: MockWebServer

    override fun createRepository(): ChatRepository {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/health") ->
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")

                    path.contains("/stream") ->
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(if (path.contains("from=2")) DONE_ONLY else FULL_STREAM)

                    else ->
                        MockResponse().setResponseCode(200).setBody("""{"requestId":"r1"}""")
                }
            }
        }
        server.start()
        return HttpChatRepository(server.url("/"))
    }

    @Test
    fun `非 2xx 的 POST 会抛 IOException`() {
        // 单独起一个只会返回 500 的服务，避免影响上面的 dispatcher
        val failing = MockWebServer()
        failing.enqueue(MockResponse().setResponseCode(500))
        failing.start()
        try {
            val repository = HttpChatRepository(failing.url("/"))
            val thrown = runCatching { runBlocking { repository.send("c", "m") } }.exceptionOrNull()
            assertTrue("期望 IOException，实际：$thrown", thrown is java.io.IOException)
        } finally {
            failing.shutdown()
        }
    }

    companion object {
        private const val FULL_STREAM =
            "id: 1\nevent: text\ndata: {\"delta\":\"echo: \"}\n\n" +
                "id: 2\nevent: text\ndata: {\"delta\":\"hello\"}\n\n" +
                "id: 3\nevent: done\ndata: {\"finishReason\":\"stop\"}\n\n"

        private const val DONE_ONLY = "id: 3\nevent: done\ndata: {\"finishReason\":\"stop\"}\n\n"
    }
}