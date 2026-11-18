package com.termux.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 协议模型与 JSON 解析的测试（契约 C 的 L1）。
 *
 * 其中 fixture 用例是**跨语言契约**：Kotlin 侧与 Python 侧读同一份
 * `tests/fixtures/sse/basic.jsonl`（契约 C 的 C2，禁止两侧各存一份）。
 */
class ChatEventTest {

    @Test
    fun `decode 能把 B4 的六种事件都解出来`() {
        assertEquals(ChatEvent.Text("hi"), ChatEvent.decode("text", """{"delta":"hi"}"""))
        assertEquals(
            ChatEvent.ToolCall("ls", mapOf("path" to "~")),
            ChatEvent.decode("tool_call", """{"name":"ls","args":{"path":"~"}}""")
        )
        assertEquals(
            ChatEvent.ToolResult("ls", "bin"),
            ChatEvent.decode("tool_result", """{"name":"ls","output":"bin"}""")
        )
        assertEquals(
            ChatEvent.FileRef("/a.txt", "text/plain", 12, "/files/a.txt"),
            ChatEvent.decode("file", """{"path":"/a.txt","mime":"text/plain","size":12,"url":"/files/a.txt"}""")
        )
        assertEquals(ChatEvent.Error("boom"), ChatEvent.decode("error", """{"message":"boom"}"""))
        assertEquals(ChatEvent.Done("stop"), ChatEvent.decode("done", """{"finishReason":"stop"}"""))
    }

    @Test
    fun `白名单外的事件必须显式失败`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChatEvent.decode("unknown_event", "{}")
        }
        assertTrue(error.message!!.contains("白名单"))
    }

    @Test
    fun `空载荷的 done 也能解出默认 finishReason`() {
        assertEquals(ChatEvent.Done("stop"), ChatEvent.decode("done", ""))
    }

    @Test
    fun `JSON 解析支持转义与非 ASCII`() {
        val payload = Json.parseObject("""{"delta":"第一行\n第二行\u4e2d","n":3}""")
        assertEquals("第一行\n第二行中", Json.string(payload, "delta"))
        assertEquals(3L, Json.long(payload, "n"))
    }

    @Test
    fun `共享 fixture 形状正确且能被 Kotlin 侧消费`() {
        val events = readFixture("basic.jsonl")
        assertEquals((1L..events.size.toLong()).toList(), events.map { it.first })
        events.forEach { (_, name, _) -> assertTrue(name in ChatEvent.ALLOWED_NAMES) }
        assertEquals(ChatEvent.DONE, events.last().second)
        events.forEach { (_, name, data) ->
            val event = ChatEvent.decode(name, data)
            if (event is ChatEvent.Done) {
                assertTrue(event.finishReason in ChatEvent.ALLOWED_FINISH_REASONS)
            }
        }
    }

    /** 每行形如 `{"id":1,"event":"text","data":{...}}`，返回 (id, event, data 原文)。 */
    private fun readFixture(name: String): List<Triple<Long, String, String>> {
        val file = locateFixture(name)
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val outer = Json.parseObject(line)
                Triple(
                    // fixture 里的 id 是 JSON 数字，不是字符串
                    Json.long(outer, "id"),
                    Json.string(outer, "event"),
                    // data 是嵌套对象，用原始切片交回给 decode，避免重新序列化引入差异
                    line.substringAfter("\"data\":").trim().removeSuffix("}")
                )
            }
    }

    private fun locateFixture(name: String): File {
        var directory: File? = File(System.getProperty("user.dir"))
        while (directory != null) {
            val candidate = File(directory, "tests/fixtures/sse/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw IllegalStateException("找不到 fixture $name（从 ${System.getProperty("user.dir")} 向上检索）")
    }
}