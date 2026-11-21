package com.termux.app.chat

/**
 * 极简 JSON 解析器（只解析 Bridge 的载荷）。
 *
 * 为什么不用 `org.json`：它在 JVM 单元测试里是 android.jar 的空壳，调用会抛
 * "not mocked"，会把协议层测试硬拖进 Robolectric。这里自带实现，主代码与
 * JVM 测试走同一套逻辑，零依赖，也符合契约 C 的 C0「协议层必须能在桌面验证」。
 *
 * 只支持 JSON 标准语法的最小集合：对象、数组、字符串（含 \uXXXX 转义）、
 * 数字、true/false/null。Bridge 的载荷就是这些。
 */
object Json {

    fun parseObject(text: String): Map<String, Any?> {
        val value = parseAny(text)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw IllegalArgumentException("载荷不是 JSON 对象")
    }

    fun parseArray(text: String): List<Any?> {
        val value = parseAny(text)
        @Suppress("UNCHECKED_CAST")
        return value as? List<Any?> ?: throw IllegalArgumentException("载荷不是 JSON 数组")
    }

    private fun parseAny(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "JSON 末尾有多余内容，位置 ${parser.position}" }
        return value
    }

    fun string(source: Map<String, Any?>, key: String, fallback: String = ""): String =
        source[key] as? String ?: fallback

    fun long(source: Map<String, Any?>, key: String, fallback: Long = 0L): Long =
        when (val value = source[key]) {
            is Double -> value.toLong()
            is String -> value.toLongOrNull() ?: fallback
            else -> fallback
        }

    fun map(source: Map<String, Any?>, key: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return source[key] as? Map<String, Any?> ?: emptyMap()
    }

    fun maps(source: Map<String, Any?>, key: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return (source[key] as? List<Any?>).orEmpty().filterIsInstance<Map<String, Any?>>()
    }

    fun mapArray(items: List<Any?>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return items.filterIsInstance<Map<String, Any?>>()
    }

    private class Parser(private val text: String) {

        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(position < text.length) { "JSON 意外结束" }
            return when (text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, position)) { "非法字面量，位置 $position" }
            position += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++ // '{'
            skipWhitespace()
            if (position < text.length && text[position] == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(position < text.length && text[position] == ':') { "对象缺少 ':'，位置 $position" }
                position++
                result[key] = readValue()
                skipWhitespace()
                require(position < text.length) { "对象未闭合" }
                when (text[position]) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> throw IllegalArgumentException("对象期待 ',' 或 '}'，位置 $position")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            position++ // '['
            skipWhitespace()
            if (position < text.length && text[position] == ']') {
                position++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                require(position < text.length) { "数组未闭合" }
                when (text[position]) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> throw IllegalArgumentException("数组期待 ',' 或 ']'，位置 $position")
                }
            }
        }

        private fun readString(): String {
            require(position < text.length && text[position] == '"') { "字符串未以引号开始，位置 $position" }
            position++
            val builder = StringBuilder()
            while (true) {
                require(position < text.length) { "字符串未闭合" }
                when (val current = text[position]) {
                    '"' -> {
                        position++
                        return builder.toString()
                    }
                    '\\' -> {
                        position++
                        require(position < text.length) { "转义符后意外结束" }
                        when (val escaped = text[position]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(position + 5 <= text.length) { "\\u 转义不完整" }
                                val hex = text.substring(position + 1, position + 5)
                                builder.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$escaped")
                        }
                        position++
                    }
                    else -> {
                        builder.append(current)
                        position++
                    }
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            if (position < text.length && text[position] == '-') position++
            while (position < text.length && (text[position].isDigit() || text[position] in ".eE+-")) position++
            val raw = text.substring(start, position)
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字 '$raw'，位置 $start")
        }
    }
}