package com.openlog.utils

import com.openlog.model.LogEntry
import com.openlog.model.LogTab
import java.io.File

// Always the full filtered data (visibleEntries — same source computeItems() uses), regardless
// of collapsed/expanded sequence or stack-trace headers: those are a viewing convenience, not a
// filter, so an export should never silently drop lines a user just happened to have folded.
private fun txtLine(r: LogEntry): String = "${r.ts}  ${r.level.key}/${r.tag}  ${r.msg}"

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

private fun csvLine(r: LogEntry): String {
    val fields = listOf(r.ts, r.level.key.toString(), r.tag, r.pid.toString(), r.tid.toString(), r.msg)
    return fields.joinToString(",") { csvField(it) }
}

// String-returning builders — kept for callers that want the content in memory (existing tests
// use these as the parity oracle for exportFilteredToFile below).
fun buildFilteredTxt(tab: LogTab): String = buildString {
    visibleEntries(tab).forEach { r -> appendLine(txtLine(r)) }
}

fun buildFilteredCsv(tab: LogTab): String = buildString {
    appendLine("ts,level,tag,pid,tid,msg")
    visibleEntries(tab).forEach { r -> appendLine(csvLine(r)) }
}

// Streams the same content buildFilteredTxt/buildFilteredCsv produce straight to [destination] —
// one row at a time through writeFileAtomically — instead of materializing the entire export as a
// single in-memory String first (P-03: unbounded allocation proportional to output size). The
// write is also crash-safe: writeFileAtomically only replaces destination once every row has been
// written successfully, so a failure or cancellation partway through never corrupts or truncates
// an existing export at that path.
fun exportFilteredToFile(tab: LogTab, destination: File, csv: Boolean) {
    writeFileAtomically(destination) { writer ->
        if (csv) writer.appendLine("ts,level,tag,pid,tid,msg")
        visibleEntries(tab).forEach { r -> writer.appendLine(if (csv) csvLine(r) else txtLine(r)) }
    }
}
