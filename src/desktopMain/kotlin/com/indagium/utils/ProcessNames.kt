package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.ProcessNameMode

// Logcat never gives openLog a pid -> process-name map directly, but the framework itself logs
// process starts in a couple of well-known shapes. This scans for those and builds one — the
// side map LogAnalysis.processNames carries (see model/Model.kt's own doc for why it's a side
// map, never a LogEntry field).
//
// Two tags carry a usable (pid, name) pair; both are gated by exact tag equality FIRST, mirroring
// StackTraceComputer's ANR_TAG/NATIVE_CRASH_TAG gates (utils/StackTraceComputer.kt:21-27) — every
// line whose tag is neither of these two exact strings is skipped before any regex runs, which is
// the vast majority of a real log. That gate is the entire point: StackTraceComputer's own
// MsgScanner comment (utils/StackTraceComputer.kt:29-33) records ~14s of a ~19s 10M-line load
// going to per-line contains()/regex work when it isn't gated this cheaply first.
//
//  1. ActivityManager: "Start proc <pid>:<name>/<uid> for ..." — the common, current-day format.
//  2. am_proc_start (event-buffer tag): "[<user>,<pid>,<uid>,<name>,<type>,<component>]".
//
// A third, OLDER ActivityManager shape — "Start proc <name> for broadcast <name>/<component>",
// with no pid anywhere in the line — is deliberately left unparsed: proc-start formats vary
// across Android versions/OEMs (see utils/StackTraceComputer.kt:181-184's ANR/native-crash
// warning, the same caution applies here), and this shape has nothing to anchor a pid to. Coverage
// stays narrow and correct rather than broad and guessed — PROC_START_MSG_RE's leading "Start proc
// <digits>:" requirement already excludes it without any special-casing.
private const val PROC_START_TAG = "ActivityManager"
private const val PROC_START_EVENT_TAG = "am_proc_start"

// "Start proc 12345:com.example.app/u0a123 for activity com.example.app/.MainActivity" — captures
// the pid and everything up to the next '/' (the uid field) as the process name. Process names can
// themselves carry a ':' (e.g. "com.example.app:remote" for a secondary process) but never a '/'
// or whitespace before the uid field, so `[^/\s]+` is both permissive enough for real names and
// tight enough not to swallow the rest of the line.
private val PROC_START_MSG_RE = Regex("""^Start proc (\d+):([^/\s]+)/""")

// am_proc_start's event-buffer payload: "[user,pid,uid,processName,type,componentName]" (AOSP's
// am_proc_start event-log tag definition). Only the pid (field 1) and process name (field 3) are
// needed; type/component are ignored.
private val PROC_START_EVENT_RE = Regex("""^\[\s*\d+\s*,\s*(\d+)\s*,\s*\d+\s*,\s*([^,\]]+)\s*,""")

/**
 * Scans [logData] for process-start lines and returns the pid -> process-name map they describe.
 * Pure and single-pass, same shape as computeStackTraceGroups/computeCrashSites — safe to call
 * from both the immediate ("pending") and deferred analysis tiers, and over just a tail batch's
 * new entries for an incremental merge (see TailCoordinator.appendTailedLines).
 *
 * Pid reuse: if the same pid starts more than once in [logData] (a long-running or heavily
 * recycled log), the LAST occurrence wins — this is a simple forward scan that overwrites the
 * map entry each time a proc-start line matches, so later starts naturally supersede earlier
 * ones. That is also what makes the tail-merge incremental (existing + computeProcessNames(new))
 * correct without re-scanning the whole file: a newer batch's names are meant to override older
 * ones for the same pid.
 */
fun computeProcessNames(logData: List<LogEntry>): Map<Int, String> {
    if (logData.isEmpty()) return emptyMap()
    val names = LinkedHashMap<Int, String>()
    for (entry in logData) {
        val (pid, name) = entry.procStartPidAndName() ?: continue
        names[pid] = name
    }
    return names
}

// The per-line half of the scan, split out so the loop above carries a single `continue` rather
// than one per failed parse step (detekt LoopWithTooManyJumpStatements). Tag equality is checked
// first here, exactly as before — the `when` is what keeps every non-proc-start line away from a
// regex, and the vast majority of a real log never gets past it.
private fun LogEntry.procStartPidAndName(): Pair<Int, String>? = when (tag) {
    PROC_START_TAG ->
        // startsWith gate before the regex: "Start proc " is a cheap prefix check, and
        // ActivityManager logs plenty of other things.
        if (msg.startsWith("Start proc ")) PROC_START_MSG_RE.find(msg)?.pidAndName() else null

    PROC_START_EVENT_TAG -> PROC_START_EVENT_RE.find(msg)?.pidAndName()
    else -> null
}

// Both patterns capture the pid in group 1 and the process name in group 2, so they share this.
private fun MatchResult.pidAndName(): Pair<Int, String>? {
    val pid = groupValues[1].toIntOrNull() ?: return null
    val name = groupValues[2].trim()
    return if (pid > 0 && name.isNotBlank()) pid to name else null
}

/**
 * Resolves what the PID cell should show for [pid] under [mode] — the resolved process name, or
 * null when the bare pid number should render instead. Pulled out of ui/LogViewer.kt's LogRow (the
 * only production caller) as a pure function so the OFF/ALL/MANUAL decision is unit-testable
 * without a Compose test harness (this codebase has none — see ProcessNamesTest.kt).
 *
 * - OFF always returns null — the PID cell renders exactly as it did before this feature existed.
 * - ALL returns [processNames]'s entry for [pid], or null if this pid's name was never learned.
 * - MANUAL returns the same, but ONLY when [pid] is also in [manualPicks] — see
 *   model/Model.kt's LogTab.manualProcessNamePicks for why that set is session-only.
 */
fun resolveProcessDisplayName(
    mode: ProcessNameMode,
    processNames: Map<Int, String>,
    manualPicks: Set<Int>,
    pid: Int,
): String? = when (mode) {
    ProcessNameMode.OFF -> null
    ProcessNameMode.ALL -> processNames[pid]
    ProcessNameMode.MANUAL -> processNames[pid]?.takeIf { pid in manualPicks }
}
