package com.openlog.utils

import com.openlog.model.LogTab

// Always the full filtered data (visibleEntries — same source computeItems() uses), regardless
// of collapsed/expanded sequence or stack-trace headers: those are a viewing convenience, not a
// filter, so an export should never silently drop lines a user just happened to have folded.
fun buildFilteredTxt(tab: LogTab): String = buildString {
    visibleEntries(tab).forEach { r -> appendLine("${r.ts}  ${r.level.key}/${r.tag}  ${r.msg}") }
}

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

fun buildFilteredCsv(tab: LogTab): String = buildString {
    appendLine("ts,level,tag,pid,tid,msg")
    visibleEntries(tab).forEach { r ->
        val fields = listOf(r.ts, r.level.key.toString(), r.tag, r.pid.toString(), r.tid.toString(), r.msg)
        appendLine(fields.joinToString(",") { csvField(it) })
    }
}
