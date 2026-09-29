package com.indagium.utils

// Reader for Qt's QSettings INI format, the file klogg exports its highlighter sets in. UI-free and
// dependency-free, mirroring qsettings.cpp: a "[Section]" prefixes every key in it with "Section/",
// nested groups and QSettings arrays are spelled with backslashes in the key ("sets\1\name" is the
// key "sets/1/name"), keys are %XX / %UXXXX escaped, values are C-style escaped strings that may be
// quoted, and an unquoted comma makes a value a string list. The "@Type(...)" byte-blob spellings
// (@Variant, @ByteArray, ...) are not decoded; the keys that carry them are reported instead.

/** One decoded value. [isList] is true when the raw text was a comma-separated list. */
internal data class QSettingsValue(val items: List<String>, val isList: Boolean) {
    /** The value as one string. A list is re-joined the way QSettings would have written it. */
    val text: String get() = items.joinToString(", ")
}

internal class QSettingsIni private constructor(
    private val values: Map<String, QSettingsValue>,
    /** Full keys whose value is an @Variant/@ByteArray/... blob this reader does not decode. */
    val undecodedKeys: List<String>,
) {
    fun contains(key: String): Boolean = key in values

    fun value(key: String): QSettingsValue? = values[key]

    fun string(key: String): String? = values[key]?.text

    fun stringList(key: String): List<String>? = values[key]?.items

    fun int(key: String): Int? = string(key)?.trim()?.toIntOrNull()

    fun bool(key: String): Boolean? = when (string(key)?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    // Sorted view of the keys, so everything under one prefix is a contiguous range: '0' is the
    // character right after '/', so [prefix/, prefix0) is exactly the keys starting with "prefix/".
    private val sortedKeys: java.util.TreeSet<String> by lazy { java.util.TreeSet(values.keys) }

    private fun keysUnder(prefix: String): Set<String> = sortedKeys.subSet("$prefix/", "${prefix}0")

    /** Whether any key lives under [prefix] + "/". */
    fun hasGroup(prefix: String): Boolean = keysUnder(prefix).isNotEmpty()

    /**
     * The QSettings array indices that actually have at least one key under "[prefix]/<n>/", ascending.
     * One pass over that prefix's keys, so it costs what is there, not what a "size" key claims.
     */
    fun arrayIndices(prefix: String): List<Int> {
        val found = java.util.TreeSet<Int>()
        val skip = prefix.length + 1
        for (key in keysUnder(prefix)) {
            val slash = key.indexOf('/', skip)
            if (slash < 0) continue
            val n = key.substring(skip, slash).toIntOrNull() ?: continue
            if (n >= 1 && n.toString() == key.substring(skip, slash)) found += n
        }
        return found.toList()
    }

    val keys: Set<String> get() = values.keys

    companion object {
        @Suppress("LoopWithTooManyJumpStatements")
        fun parse(text: String): QSettingsIni {
            val values = LinkedHashMap<String, QSettingsValue>()
            val undecoded = mutableListOf<String>()
            var section = ""
            val lines = text.removePrefix("﻿").split(LINE_BREAK)
            var i = 0
            while (i < lines.size) {
                var line = lines[i++].trim()
                if (line.isEmpty() || line[0] == ';' || line[0] == '#') continue
                if (line[0] == '[' && line.endsWith("]")) {
                    section = unescapeKey(line.substring(1, line.length - 1).replace('\\', '/'))
                    continue
                }
                // A value whose line ends in an odd number of backslashes continues on the next line.
                while (endsWithOddBackslashes(line) && i < lines.size) {
                    line = line.dropLast(1) + lines[i++].trim()
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = unescapeKey(line.substring(0, eq).trim().replace('\\', '/'))
                val fullKey = if (section.isEmpty() || section == "General") key else "$section/$key"
                val raw = line.substring(eq + 1).trim()
                val decoded = decodeRaw(raw)
                if (decoded == null) undecoded += fullKey else values[fullKey] = decoded
            }
            return QSettingsIni(values, undecoded)
        }

        private val LINE_BREAK = Regex("\r\n|\n|\r")

        private fun endsWithOddBackslashes(line: String): Boolean = (line.length - line.trimEnd('\\').length) % 2 == 1

        // iniUnescapedKey: '\' is the group separator, %XX and %UXXXX are escaped characters.
        private fun unescapeKey(key: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < key.length) {
                val c = key[i]
                if (c == '%' && i + 1 < key.length) {
                    val digits = if (key[i + 1] == 'U') 4 else 2
                    val start = if (key[i + 1] == 'U') i + 2 else i + 1
                    val hex = key.substring(start, minOf(key.length, start + digits))
                    val code = if (hex.length == digits) hex.toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i = start + digits
                        continue
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        // null = an @Type(...) blob this reader does not decode.
        private fun decodeRaw(raw: String): QSettingsValue? {
            if (raw.startsWith("@@")) return decodeItems("@" + raw.substring(2))
            if (raw == "@Invalid()") return QSettingsValue(emptyList(), isList = true)
            if (raw.startsWith("@") && raw.contains('(') && raw.endsWith(")")) return null
            return decodeItems(raw)
        }

        // iniUnescapedStringList: quotes and escapes protect characters (including whitespace) from
        // trimming; an unquoted comma ends an item and marks the value as a list.
        @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        private fun decodeItems(raw: String): QSettingsValue {
            val items = mutableListOf<String>()
            val cur = StringBuilder()
            var protectedLen = 0
            var inQuotes = false
            var isList = false

            fun endItem() {
                var end = cur.length
                while (end > protectedLen && cur[end - 1].isWhitespace()) end--
                items += cur.substring(0, end)
                cur.setLength(0)
                protectedLen = 0
            }

            var i = 0
            while (i < raw.length) {
                val c = raw[i++]
                when {
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> {
                        isList = true
                        endItem()
                    }
                    c == '\\' && i < raw.length -> {
                        val e = raw[i++]
                        when (e) {
                            'a' -> cur.append('\u0007')
                            'b' -> cur.append('\b')
                            'f' -> cur.append('\u000C')
                            'n' -> cur.append('\n')
                            'r' -> cur.append('\r')
                            't' -> cur.append('\t')
                            'v' -> cur.append('\u000B')
                            'x' -> {
                                var code = 0
                                var n = 0
                                while (i < raw.length && n < 4 && raw[i].digitToIntOrNull(16) != null) {
                                    code = code * 16 + raw[i].digitToInt(16)
                                    i++
                                    n++
                                }
                                cur.append(code.toChar())
                            }
                            in '0'..'7' -> {
                                var code = e - '0'
                                var n = 1
                                while (i < raw.length && n < 3 && raw[i] in '0'..'7') {
                                    code = code * 8 + (raw[i] - '0')
                                    i++
                                    n++
                                }
                                cur.append(code.toChar())
                            }
                            else -> cur.append(e)
                        }
                        protectedLen = cur.length
                    }
                    else -> {
                        if (inQuotes || !(c.isWhitespace() && cur.isEmpty())) cur.append(c)
                        if (inQuotes) protectedLen = cur.length
                    }
                }
            }
            endItem()
            return QSettingsValue(items, isList)
        }
    }
}
