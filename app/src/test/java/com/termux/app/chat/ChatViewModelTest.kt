package com.termux.app.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
        assertEquals(3L, state.lastEventId)
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
        assertEquals(3L, state.lastEventId)
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
        // 第二次在 streaming=true 时被丢弃，因此只发出一条用户消息
        assertEquals(1, viewModel.state.value.messages.count { it.role == ChatViewModel.Role.USER })
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