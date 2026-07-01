package com.openlog.utils

import com.openlog.model.CrashKind
import com.openlog.model.CrashSite
import com.openlog.model.LogEntry
import com.openlog.model.StackTraceGroup

private val EXCEPTION_HEADER_RE = Regex("""^[\w.$]+(Exception|Error)(:.*)?$""")
private val AT_FRAME_RE = Regex("""^\s*at\s+\S+""")
private val CAUSED_BY_RE = Regex("""^Caused by:""")
private val MORE_FRAMES_RE = Regex("""^\s*\.\.\.\s+\d+\s+more$""")
private val PROCESS_LINE_RE = Regex("""^Process:\s+\S+,\s*PID:\s*\d+""")
private val ANR_MSG_RE = Regex("""ANR in\s+\S+""")
private const val ANR_TAG = "ActivityManager"

private fun isTrigger(msg: String): Boolean {
    val trimmed = msg.trim()
    return trimmed.contains("FATAL EXCEPTION", ignoreCase = true) || EXCEPTION_HEADER_RE.matches(trimmed)
}

// Continuation lines that extend an open trace no matter how many members it already has.
private fun isUnconditionalContinuation(msg: String): Boolean {
    val trimmed = msg.trim()
    return AT_FRAME_RE.containsMatchIn(trimmed) ||
        CAUSED_BY_RE.containsMatchIn(trimmed) ||
        MORE_FRAMES_RE.matches(trimmed) ||
        PROCESS_LINE_RE.containsMatchIn(trimmed)
}

// Always-on (no user configuration), unlike computeSeqGroups()'s user-defined SequenceDefs —
// auto-folds Java/Kotlin exception dumps (FATAL EXCEPTION / <Class>Exception headers, "at ...”
// frames, "Caused by:" chains, "... N more") into one collapsible group.
//
// Single O(n) linear pass with one open trace tracked per pid — this runs on every
// computeItems() call (every filter/expand keystroke), so it must stay cheap, unlike
// computeSeqGroups()'s O(n^2) worst case. Continuation lines are scoped to the SAME pid as their
// trigger line: logcat interleaves processes, so another pid's line must neither extend nor
// break an open trace — tracking one open trace per pid (rather than one global slot) lets an
// unrelated pid's lines pass through mid-trace without disturbing it.
//
// v1 produces flat groups only (trigger + all continuation lines) — no nesting for "Caused by:"
// chains, matching the single-header requirement and avoiding a second nesting model on day one.
fun computeStackTraceGroups(logData: List<LogEntry>): List<StackTraceGroup> {
    class OpenTrace(val triggerIdx: Int) {
        val memberIds = mutableListOf<Int>()
        var sawFrame = false
    }

    val openByPid = HashMap<Int, OpenTrace>()
    val groups = mutableListOf<StackTraceGroup>()

    fun flush(pid: Int) {
        val open = openByPid.remove(pid) ?: return
        if (open.memberIds.isNotEmpty()) {
            val rid = logData[open.triggerIdx].id
            groups += StackTraceGroup(gid = "st_$rid", rid = rid, memberIds = open.memberIds.toList())
        }
    }

    for (i in logData.indices) {
        val entry = logData[i]
        val open = openByPid[entry.pid]
        if (open != null) {
            // Real crash dumps put metadata lines — "Process: <pkg>, PID: <n>", the exception
            // class-name line ("java.lang.NullPointerException: ...") — between the "FATAL
            // EXCEPTION" header and the first "at" frame. Tolerate a trigger-like line here too
            // (fold it in) as long as no frame has been seen yet. Once a frame has been seen, a
            // later trigger-like line is a genuinely new exception (back-to-back crashes), not a
            // continuation of this one.
            val isHeaderFollowUp = !open.sawFrame && isTrigger(entry.msg)
            if (isUnconditionalContinuation(entry.msg) || isHeaderFollowUp) {
                open.memberIds += entry.id
                if (AT_FRAME_RE.containsMatchIn(entry.msg.trim())) open.sawFrame = true
                continue
            }
            flush(entry.pid)
        }
        if (isTrigger(entry.msg)) {
            openByPid[entry.pid] = OpenTrace(i)
        }
    }
    openByPid.keys.toList().forEach { flush(it) }

    // Flush order follows whichever pid's trace closed first, not document order once multiple
    // pids interleave — restore document order (entry id increases monotonically per tab).
    return groups.sortedBy { it.rid }
}

// EXCEPTION sites are derived 1:1 from computeStackTraceGroups()'s output — the header line an
// exception already collapses onto is exactly what a crash panel needs to jump to.
// ANR sites are a separate single-line scan on "ActivityManager: ANR in ..." lines — real ANR
// dumps continue with Reason:/Load:/CPU usage lines, but v1 only needs the anchor line to jump
// to, not a folded group. Tag/pattern coverage is deliberately narrow (see plan's flagged risk:
// format varies across Android versions/OEMs) — broaden only once validated against real samples.
fun computeCrashSites(logData: List<LogEntry>, stackGroups: List<StackTraceGroup>): List<CrashSite> {
    val byId = logData.associateBy { it.id }
    val exceptionSites = stackGroups.mapNotNull { g ->
        byId[g.rid]?.let { entry -> CrashSite(id = "crash_${g.rid}", entry = entry, kind = CrashKind.EXCEPTION, groupGid = g.gid) }
    }
    val anrSites = logData
        .filter { it.tag == ANR_TAG && ANR_MSG_RE.containsMatchIn(it.msg) }
        .map { CrashSite(id = "crash_${it.id}", entry = it, kind = CrashKind.ANR, groupGid = null) }
    return (exceptionSites + anrSites).sortedBy { it.entry.id }
}
