package com.openlog.utils

import com.openlog.model.CrashKind
import com.openlog.model.CrashSite
import com.openlog.model.LogEntry
import com.openlog.model.StackTraceGroup

private val EXCEPTION_HEADER_RE = Regex("""^[\w.$]+(Exception|Error)(:.*)?$""")
private val AT_FRAME_RE = Regex("""^\s*at\s+\S+""")

// "Caused by:" continuation needs no regex: on an already-trimmed line, the old
// ^Caused by: containsMatchIn is exactly startsWith (see isUnconditionalContinuation).
private val MORE_FRAMES_RE = Regex("""^\s*\.\.\.\s+\d+\s+more$""")
private val PROCESS_LINE_RE = Regex("""^Process:\s+\S+,\s*PID:\s*\d+""")
private val EXCEPTION_PRELUDE_RE = Regex("""\b(caught|uncaught|throwing|threw)\b.*(exception|error|throwable)""", RegexOption.IGNORE_CASE)
private val ANR_MSG_RE = Regex("""ANR in\s+\S+""")
private const val ANR_TAG = "ActivityManager"

// Single left-to-right scan computing every substring gate the per-line classification needs.
// This runs for every line of the file on load; the previous per-gate contains() calls (several
// of them case-insensitive) meant up to six full scans of each message and dominated large-file
// analysis time (~14s of an ~19s load at 10M lines). Each flag is a necessary condition of the
// regex it guards, so the accepted set is unchanged; the one behavioral addition is gating the
// prelude regex on a verb match too — also a necessary condition of that regex.
private class MsgScanner {
    var fatalException = false // ci "fatal exception" — isTrigger's direct-accept substring
    var exceptionWordCs = false // cs "Exception" — EXCEPTION_HEADER_RE gate
    var errorWordCs = false // cs "Error" — EXCEPTION_HEADER_RE gate
    var preludeVerb = false // ci caught|throwing|threw ("uncaught" contains "caught")
    var preludeNoun = false // ci exception|error|throwable

    // Branchy by design: one dispatch per character class IS the optimization — splitting it
    // into per-needle helpers would reintroduce the multiple passes this scanner exists to avoid.
    @Suppress("CyclomaticComplexMethod")
    fun scan(msg: String) {
        fatalException = false
        exceptionWordCs = false
        errorWordCs = false
        preludeVerb = false
        preludeNoun = false
        val n = msg.length
        var i = 0
        while (i < n) {
            when (msg[i]) {
                'f', 'F' -> if (!fatalException && msg.ci(i, "fatal exception")) fatalException = true
                'E' -> {
                    if (!exceptionWordCs && msg.startsWith("Exception", i)) exceptionWordCs = true
                    if (!errorWordCs && msg.startsWith("Error", i)) errorWordCs = true
                    if (!preludeNoun && (msg.ci(i, "exception") || msg.ci(i, "error"))) preludeNoun = true
                }

                'e' -> if (!preludeNoun && (msg.ci(i, "exception") || msg.ci(i, "error"))) preludeNoun = true
                't', 'T' -> {
                    if (!preludeNoun && msg.ci(i, "throwable")) preludeNoun = true
                    if (!preludeVerb && (msg.ci(i, "threw") || msg.ci(i, "throwing"))) preludeVerb = true
                }

                'c', 'C' -> if (!preludeVerb && msg.ci(i, "caught")) preludeVerb = true
                else -> {}
            }
            i++
        }
    }
}

private fun String.ci(at: Int, needle: String): Boolean =
    regionMatches(at, needle, 0, needle.length, ignoreCase = true)

private fun isTrigger(scan: MsgScanner, msg: String): Boolean {
    if (scan.fatalException) return true
    if (!scan.exceptionWordCs && !scan.errorWordCs) return false
    return EXCEPTION_HEADER_RE.matches(msg.trim())
}

// Continuation lines that extend an open trace no matter how many members it already has.
private fun isUnconditionalContinuation(msg: String): Boolean {
    val trimmed = msg.trim()
    return when {
        trimmed.startsWith("at ") || trimmed.startsWith("at\t") -> AT_FRAME_RE.containsMatchIn(trimmed)
        trimmed.startsWith("Caused by:") -> true
        trimmed.startsWith("...") -> MORE_FRAMES_RE.matches(trimmed)
        trimmed.startsWith("Process:") -> PROCESS_LINE_RE.containsMatchIn(trimmed)
        else -> false
    }
}

private fun isExceptionPrelude(scan: MsgScanner, msg: String): Boolean {
    if (!scan.preludeVerb || !scan.preludeNoun) return false
    return EXCEPTION_PRELUDE_RE.containsMatchIn(msg.trim())
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
    val pendingPreludeByPid = HashMap<Int, Int>()
    val groups = mutableListOf<StackTraceGroup>()

    fun flush(pid: Int) {
        val open = openByPid.remove(pid) ?: return
        if (open.memberIds.isNotEmpty()) {
            val rid = logData[open.triggerIdx].id
            groups += StackTraceGroup(gid = "st_$rid", rid = rid, memberIds = open.memberIds.toList())
        }
    }

    val scanner = MsgScanner()
    for (i in logData.indices) {
        val entry = logData[i]
        scanner.scan(entry.msg)
        val trigger = isTrigger(scanner, entry.msg)
        val open = openByPid[entry.pid]
        if (open != null) {
            // Real crash dumps put metadata lines — "Process: <pkg>, PID: <n>", the exception
            // class-name line ("java.lang.NullPointerException: ...") — between the "FATAL
            // EXCEPTION" header and the first "at" frame. Tolerate a trigger-like line here too
            // (fold it in) as long as no frame has been seen yet. Once a frame has been seen, a
            // later trigger-like line is a genuinely new exception (back-to-back crashes), not a
            // continuation of this one.
            val isHeaderFollowUp = !open.sawFrame && trigger
            if (isUnconditionalContinuation(entry.msg) || isHeaderFollowUp) {
                open.memberIds += entry.id
                if (AT_FRAME_RE.containsMatchIn(entry.msg.trim())) open.sawFrame = true
                continue
            }
            flush(entry.pid)
        }
        if (trigger) {
            val preludeIdx = pendingPreludeByPid[entry.pid]
                ?.takeIf { it == i - 1 && logData[it].tag == entry.tag }
            openByPid[entry.pid] = OpenTrace(preludeIdx ?: i).also { trace ->
                if (preludeIdx != null) trace.memberIds += entry.id
            }
            pendingPreludeByPid.remove(entry.pid)
        } else if (isExceptionPrelude(scanner, entry.msg)) {
            pendingPreludeByPid[entry.pid] = i
        } else {
            pendingPreludeByPid.remove(entry.pid)
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
    // Binary-search view, not a HashMap copy — only a handful of rids ever get looked up.
    val byId = EntryIdMap(logData)
    val exceptionSites = stackGroups.mapNotNull { g ->
        byId[g.rid]?.let { entry -> CrashSite(id = "crash_${g.rid}", entry = entry, kind = CrashKind.EXCEPTION, groupGid = g.gid) }
    }
    val anrSites = logData
        .filter { it.tag == ANR_TAG && ANR_MSG_RE.containsMatchIn(it.msg) }
        .map { CrashSite(id = "crash_${it.id}", entry = it, kind = CrashKind.ANR, groupGid = null) }
    return (exceptionSites + anrSites).sortedBy { it.entry.id }
}
