package com.openlog.debug

// Minimal hand-rolled JSON encode/decode for ControlServer's flat request/response DTOs — no
// kotlinx.serialization dependency for what's only ever String/Number/Boolean/List/Map/null.
object Json {
    fun encode(value: Any?): String = StringBuilder().also { encodeTo(it, value) }.toString()

    private fun encodeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> encodeString(sb, value)
            is Boolean -> sb.append(value.toString())
            is Int, is Long, is Double, is Float -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    encodeString(sb, k.toString())
                    sb.append(':')
                    encodeTo(sb, v)
                }
                sb.append('}')
            }
            is Set<*> -> encodeTo(sb, value.toList())
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    encodeTo(sb, v)
                }
                sb.append(']')
            }
            else -> encodeString(sb, value.toString())
        }
    }

    private fun encodeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun decode(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            return when {
                i >= s.length -> null
                s[i] == '{' -> parseObject()
                s[i] == '[' -> parseArray()
                s[i] == '"' -> parseString()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (i < s.length && s[i] == ':') i++
                map[key] = parseValue()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                break
            }
            skipWs()
            if (i < s.length && s[i] == '}') i++
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                break
            }
            skipWs()
            if (i < s.length && s[i] == ']') i++
            return list
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, minOf(i + 5, s.length))
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(s[i])
                    }
                } else {
                    sb.append(s[i])
                }
                i++
            }
            i++ // closing quote
            return sb.toString()
        }

        private fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val text = s.substring(start, i)
            return text.toIntOrNull() ?: text.toDoubleOrNull() ?: 0
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.str(key: String): String? = this[key] as? String

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.strList(key: String): List<String>? = (this[key] as? List<Any?>)?.map { it.toString() }

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.intList(key: String): List<Int>? = (this[key] as? List<Any?>)?.mapNotNull {
    when (it) {
        is Int -> it
        is Double -> it.toInt()
        else -> it.toString().toIntOrNull()
    }
}

fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Double -> value.toInt()
    else -> value?.toString()?.toIntOrNull()
}
