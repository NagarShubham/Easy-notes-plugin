package com.example.notes.io

/**
 * Minimal, dependency-free JSON reader/writer covering exactly what note
 * import/export needs: objects, arrays, strings, numbers, booleans and null,
 * with full string (un)escaping including `\uXXXX`.
 *
 * This replaces the ~300 KB bundled Gson dependency; since the note schema is
 * small and fully under our control, a focused parser keeps the plugin jar tiny
 * while remaining robust for arbitrary imported files.
 */
internal object Json {

    /** Escapes a string for embedding in a JSON document (without quotes). */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Parses a JSON document into a tree of [Map]/[List]/[String]/[Long]/
     * [Double]/[Boolean]/`null`. Throws [JsonException] on malformed input.
     */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Trailing content at index ${parser.index}")
        return value
    }

    private class Parser(private val s: String) {
        var index = 0
            private set

        fun atEnd(): Boolean = index >= s.length

        fun skipWhitespace() {
            while (index < s.length && s[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonException("Unexpected end of input")
            return when (val c = s[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c in '0'..'9') parseNumber()
                else throw JsonException("Unexpected character '$c' at index $index")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            index++ // consume '{'
            skipWhitespace()
            if (peek() == '}') { index++; return map }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("Expected string key at index $index")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') throw JsonException("Expected ':' at index $index")
                index++
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return map }
                    else -> throw JsonException("Expected ',' or '}' at index $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            index++ // consume '['
            skipWhitespace()
            if (peek() == ']') { index++; return list }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return list }
                    else -> throw JsonException("Expected ',' or ']' at index $index")
                }
            }
        }

        private fun parseString(): String {
            index++ // consume opening quote
            val sb = StringBuilder()
            while (index < s.length) {
                when (val c = s[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= s.length) break
                        when (val esc = s[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (index + 4 > s.length) throw JsonException("Truncated \\u escape at index $index")
                                val hex = s.substring(index, index + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("Invalid \\u escape '$hex' at index $index")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw JsonException("Invalid escape '\\$esc' at index $index")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonException("Unterminated string")
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (index < s.length && s[index] in '0'..'9') index++
            var isDouble = false
            if (index < s.length && s[index] == '.') {
                isDouble = true
                index++
                while (index < s.length && s[index] in '0'..'9') index++
            }
            if (index < s.length && (s[index] == 'e' || s[index] == 'E')) {
                isDouble = true
                index++
                if (index < s.length && (s[index] == '+' || s[index] == '-')) index++
                while (index < s.length && s[index] in '0'..'9') index++
            }
            val token = s.substring(start, index)
            return if (isDouble) token.toDouble() else (token.toLongOrNull() ?: token.toDouble())
        }

        private fun parseBoolean(): Boolean = when {
            s.startsWith("true", index) -> { index += 4; true }
            s.startsWith("false", index) -> { index += 5; false }
            else -> throw JsonException("Invalid literal at index $index")
        }

        private fun parseNull(): Any? {
            if (s.startsWith("null", index)) { index += 4; return null }
            throw JsonException("Invalid literal at index $index")
        }

        private fun peek(): Char = if (index < s.length) s[index] else '\u0000'
    }
}

/** Thrown when a JSON document cannot be parsed. */
class JsonException(message: String) : RuntimeException(message)
