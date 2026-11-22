package com.termux.app.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel 状态机测试（契约 C 的 L3）。
 *
 * 全部跑在 `StandardTestDispatcher` 上，禁 `Thread.sleep`（契约 C 的 C4/C10）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `发送后累积流式文本并在 done 后停止`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(FakeChatRepository(), "c1")

        viewModel.send("hello")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.messages.size)
        assertEquals(ChatViewModel.Role.USER, state.messages[0].role)
        assertEquals("hello", state.messages[0].text)
        assertEquals(ChatViewModel.Role.ASSISTANT, state.messages[1].role)
        assertTrue(state.messages[1].text.contains("hello"))
        assertFalse(state.streaming)
        assertFalse(state.messages[1].streaming)
        assertNull(state.error)
    }

    @Test
    fun `断线后用 from 续传且只重试一次`() = runTest(dispatcher) {
        val repository = FakeChatRepository(failAfterFrames = 1)
        val viewModel = ChatViewModel(repository, "c1")

        viewModel.send("hello")
        advanceUntilIdle()

        // 第一次拿到 null（从头），第二次带上次收到的 id=1（契约 B 的 B5：不含该 id）
        assertEquals(listOf(null, 1L), repository.streamCalls.map { it.second })

        val state = viewModel.state.value
        assertNull(state.error)
        assertTrue(state.messages[1].text.contains("hello"))
    }

    @Test
    fun `空输入与流式中的重复发送都会被忽略`() = runTest(dispatcher) {
        val repository = FakeChatRepository()
        val viewModel = ChatViewModel(repository, "c1")

        viewModel.send("   ")
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.messages.size)

        viewModel.send("first")
        viewModel.send("second")
        advanceUntilIdle()
        // 第二次在同一会话 streaming 时被丢弃，因此只发出一条用户消息
        assertEquals(1, viewModel.state.value.messages.count { it.role == ChatViewModel.Role.USER })
    }

    @Test
    fun `生成中可以切到别的会话且旧会话继续生成`() = runTest(dispatcher) {
        val repository = SwitchableRepository()
        val viewModel = ChatViewModel(repository, "c1")

        viewModel.send("hello")
        advanceTimeBy(150)
        runCurrent()
        assertTrue(viewModel.state.value.streaming)
        assertEquals("回复:c1", viewModel.state.value.messages.last().text)

        viewModel.openConversation("c2")
        assertEquals("c2", viewModel.state.value.conversationId)
        assertFalse(viewModel.state.value.streaming)
        assertTrue(viewModel.state.value.messages.isEmpty())

        viewModel.send("second")
        assertTrue(viewModel.state.value.streaming)

        advanceUntilIdle()
        assertFalse(viewModel.state.value.streaming)
        assertEquals("回复:c2", viewModel.state.value.messages.last().text)

        viewModel.openConversation("c1")
        advanceUntilIdle()
        assertFalse(viewModel.state.value.streaming)
        assertTrue(viewModel.state.value.messages.any { it.text == "回复:c1" })
    }

    @Test
    fun `流式未结束时切回原会话能看到实时缓存`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(SwitchableRepository(), "c1")

        viewModel.send("hello")
        advanceTimeBy(150)
        runCurrent()
        viewModel.openConversation("c2")
        viewModel.openConversation("c1")

        assertTrue(viewModel.state.value.streaming)
        assertEquals(2, viewModel.state.value.messages.size)
        assertEquals("回复:c1", viewModel.state.value.messages.last().text)

        advanceUntilIdle()
        assertFalse(viewModel.state.value.streaming)
        assertEquals(2, viewModel.state.value.messages.size)
    }

    @Test
    fun `停止只影响当前会话`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(SwitchableRepository(), "c1")

        viewModel.send("hello")
        advanceTimeBy(150)
        runCurrent()
        viewModel.openConversation("c2")
        viewModel.send("second")
        viewModel.stop()

        assertFalse(viewModel.state.value.streaming)
        assertEquals("已停止。", viewModel.state.value.messages.last().text)

        viewModel.openConversation("c1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.messages.any { it.text == "回复:c1" })
    }

    @Test
    fun `新建对话不打断旧会话`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(SwitchableRepository(), "c1")

        viewModel.send("hello")
        advanceTimeBy(150)
        runCurrent()
        viewModel.newConversation()

        assertFalse(viewModel.state.value.streaming)
        assertTrue(viewModel.state.value.messages.isEmpty())

        advanceUntilIdle()
        viewModel.openConversation("c1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.messages.any { it.text == "回复:c1" })
    }

    @Test
    fun `健康检查失败时 connected 为 false`() = runTest(dispatcher) {
        val repository = object : ChatRepository by FakeChatRepository() {
            override suspend fun health(): Boolean = false
        }
        val viewModel = ChatViewModel(repository, "c1")

        viewModel.refreshHealth()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.connected)
    }
}

/** 每个流可被测试调度器推进的仓库：100ms 出文本、再 100ms done。 */
private class SwitchableRepository : ChatRepository {

    private val history = mutableMapOf<String, MutableList<HistoryMessage>>()
    private val requestConversation = mutableMapOf<String, String>()
    private var counter = 0

    override suspend fun health(): Boolean = true

    override suspend fun send(conversationId: String, message: String): String {
        val requestId = "req-${++counter}"
        requestConversation[requestId] = conversationId
        history.getOrPut(conversationId) { mutableListOf() } +=
            HistoryMessage("user", message, counter.toLong())
        return requestId
    }

    override fun stream(requestId: String, fromEventId: Long?): Flow<ChatFrame> = flow {
        val conversationId = requestConversation[requestId].orEmpty()
        val reply = "回复:$conversationId"
        delay(100)
        emit(ChatFrame(1, ChatEvent.Text(reply)))
        delay(100)
        emit(ChatFrame(2, ChatEvent.Done("stop")))
        history.getOrPut(conversationId) { mutableListOf() } +=
            HistoryMessage("assistant", reply, counter.toLong())
    }

    override suspend fun conversationMessages(conversationId: String, limit: Int): List<HistoryMessage> =
        history[conversationId]?.toList() ?: emptyList()
}