package com.indagium.utils

import com.indagium.model.CrashCategory
import com.indagium.model.CrashKind
import com.indagium.model.CrashSite
import com.indagium.model.CustomIssueRule
import com.indagium.model.CustomIssueSite
import com.indagium.model.IssueCategorySelection
import com.indagium.model.IssueSite
import com.indagium.model.LogEntry
import com.indagium.model.StackTraceGroup

private val EXCEPTION_HEADER_RE = Regex("""^[\w.$]+(Exception|Error)(:.*)?$""")
private val AT_FRAME_RE = Regex("""^\s*at\s+\S+""")

// Crash-signature helpers (see computeStackTraceGroups' OpenTrace.signature doc). Extracts the
// exception class (everything up to the first ':', or the whole trimmed line if there is none)
// from a line already known to match EXCEPTION_HEADER_RE.
private fun exceptionClassNameOf(headerLine: String): String = headerLine.trim().substringBefore(':').trim()

// "at <token>" -> the token, e.g. "com.app.Main.onCreate(Main.java:10)" from
// "    at com.app.Main.onCreate(Main.java:10)". Only called on lines AT_FRAME_RE already matched.
private val FRAME_TOKEN_RE = Regex("""^\s*at\s+(\S+)""")

// Packages a frame belongs to the Android/JVM runtime rather than the app's own code — used to
// pick the first APP frame as the more identifying half of an exception's signature (many crashes
// share the same top framework frame, e.g. every NPE goes through some java.lang.reflect.* call,
// but the app frame that triggered it is what actually distinguishes one bug from another).
private val FRAMEWORK_FRAME_PREFIXES = listOf(
    "java.", "javax.", "jdk.", "sun.", "com.sun.",
    "android.", "androidx.", "com.android.", "dalvik.", "libcore.",
    "kotlin.", "kotlinx.",
)

private fun isFrameworkFrame(frameToken: String): Boolean {
    val classAndMethod = frameToken.substringBefore('(')
    return FRAMEWORK_FRAME_PREFIXES.any { classAndMethod.startsWith(it) }
}

// ANR signature: normalize to the named process only — "ANR in com.example.app (com.example.app/
// .MainActivity)" and a later ANR against the same process (different activity/reason/timings)
// both reduce to the same signature.
private val ANR_PROCESS_RE = Regex("""ANR in\s+(\S+)""")

private fun anrSignature(msg: String): String {
    val proc = ANR_PROCESS_RE.find(msg)?.groupValues?.get(1) ?: msg.trim()
    return "ANR:$proc"
}

// Native-crash signature: same tombstone line, tid normalized out — "Fatal signal 11 (SIGSEGV),
// code 1, fault addr 0x0 in tid 1234" and "...in tid 5678" from a repeat crash must collapse.
private val NATIVE_CRASH_TID_RE = Regex("""\bin tid\s+\d+\b""")

private fun nativeCrashSignature(msg: String): String = "NATIVE:" + msg.trim().replace(NATIVE_CRASH_TID_RE, "in tid *")

// "Caused by:" continuation needs no regex: on an already-trimmed line, the old
// ^Caused by: containsMatchIn is exactly startsWith (see isUnconditionalContinuation).
private val MORE_FRAMES_RE = Regex("""^\s*\.\.\.\s+\d+\s+more$""")
private val PROCESS_LINE_RE = Regex("""^Process:\s+\S+,\s*PID:\s*\d+""")
private val EXCEPTION_PRELUDE_RE = Regex("""\b(caught|uncaught|throwing|threw)\b.*(exception|error|throwable)""", RegexOption.IGNORE_CASE)
private val ANR_MSG_RE = Regex("""ANR in\s+\S+""")
private const val ANR_TAG = "ActivityManager"

// Rule B (see computeStackTraceGroups' doc comment): how many entries an open trace may reach
// past its own last claimed line before it must be flushed. Measured over a sample of real
// multi-process Android logs (~70k lines, 89 well-formed dumps): every well-formed dump had a gap
// of 0 — a real Java stack dump is emitted contiguously by a single thread in one logcat write,
// with zero foreign lines interleaved between consecutive members, and that held even for a
// 355-line StackOverflowError dump. Without a bound, a standalone one-line warning that merely
// matches the exception-header shape opens a trace that a quiet pid never closes: one observed
// case stayed open for 1180 lines and absorbed 7 later repeats of itself, hiding them from their
// true position in the view. 64 is a wide safety margin over the observed legitimate maximum (0)
// that still hard-caps a runaway trace's footprint far short of four figures.
private const val MAX_TRACE_INTERLEAVE = 64

// Tombstone dumps ("debuggerd") report native (C/C++) crashes on logcat's DEBUG tag with a line
// like "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 1234".
private val NATIVE_CRASH_MSG_RE = Regex("""Fatal signal \d+""")
private const val NATIVE_CRASH_TAG = "DEBUG"

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
// Single O(n) linear pass with one open trace tracked per (pid, tid) — this runs on every
// computeItems() call (every filter/expand keystroke), so it must stay cheap, unlike
// computeSeqGroups()'s O(n^2) worst case. Continuation lines are scoped to the SAME pid+tid as
// their trigger line: a Java stack dump is always emitted by a single thread in one logcat write
// (see MAX_TRACE_INTERLEAVE's doc — in the measured sample every well-formed dump was
// single-threaded), so another (pid, tid)'s line must neither extend nor break an open trace —
// tracking one open trace per key (rather than one global slot) lets unrelated lines pass through
// mid-trace without disturbing it. Formats with no tid field (RE_TIME/RE_BARE/RE_BRIEF/RAW) leave
// tid=0 on every entry, so those degrade to plain per-pid scoping — unchanged from before Rule A.
// Keyed on a packed Long (traceKey), not a Pair/data class, to avoid an allocation per line across
// multi-million-line files.
//
// Two more rules bound an open trace's reach, both driven by the same 5-real-log measurement:
// Rule B (MAX_TRACE_INTERLEAVE, see its doc) caps how far a continuation can sit from the trace's
// last claimed line. Rule C (see flush()) requires at least one *unconditional* continuation
// member (a real "at" frame / "Caused by:" / "... N more" / "Process:" line) before a trace is
// emitted as a group — without it, a trigger-shaped line that never sees a real dump follow it
// (a one-line "<Class>Exception: msg" status warning logged over and over) would fold
// its own later repeats into a bogus "group" purely via isHeaderFollowUp, which exists for
// metadata lines immediately after a real header, not for far-apart repeats of the header itself.
//
// v1 produces flat groups only (trigger + all continuation lines) — no nesting for "Caused by:"
// chains, matching the single-header requirement and avoiding a second nesting model on day one.
//
// isFatal is decided once, at the moment the trace opens, from the trigger line's own scan —
// "FATAL EXCEPTION" headers set MsgScanner.fatalException directly; a generic <Class>Exception/
// Error header (no "fatal exception" substring) leaves it false. Metadata/classname lines folded
// in later via isHeaderFollowUp never open a new trace, so they can't change this once set.
//
// Pulled out of computeStackTraceGroups (rather than a local class there) purely to keep that
// function's own cyclomatic complexity under detekt's threshold — it doesn't capture anything
// from the enclosing scan, so nothing else about it depends on being local.
private class OpenTrace(val triggerId: Int, triggerOrdinal: Long, val isFatal: Boolean) {
    val memberIds = IntMembers()
    var sawFrame = false

    // Rule B bookkeeping: the logData index of the most recently claimed line (trigger line
    // itself until a member is added). computeStackTraceGroups checks the gap from this to the
    // current index before letting a continuation extend the trace — see MAX_TRACE_INTERLEAVE.
    var lastClaimedIdx = triggerOrdinal

    // Rule C bookkeeping: true once any member was accepted via isUnconditionalContinuation (a
    // real "at" frame, "Caused by:", "... N more", or "Process:" line) rather than only via
    // isHeaderFollowUp. flush() refuses to emit a group that never sets this — see its doc.
    var hasUnconditionalMember = false

    // Populated as the scan progresses; see the module doc comment on signature capture.
    var exceptionClassName: String? = null
    var firstFrame: String? = null
    var firstNonFrameworkFrame: String? = null

    // "" only when neither a class name nor any frame was ever seen — an exception header with no
    // useful body. Falling back to a per-rid string (rather than "") deliberately keeps two such
    // threadbare traces from different places in the file from being treated as the same signature
    // and wrongly grouped together.
    fun computeSignature(rid: Int): String {
        val cls = exceptionClassName
        val frame = firstNonFrameworkFrame ?: firstFrame
        return when {
            cls != null || frame != null -> "EXC:${cls.orEmpty()}|${frame.orEmpty()}"
            else -> "EXC:unresolved_$rid"
        }
    }

    // Captures the exception class name the first time a genuine "<Class>Exception: msg" shaped
    // line is seen — either the line that opened this trace, or a header follow-up line folded in
    // before any frame ("FATAL EXCEPTION: main" carries no class name itself; it arrives on the
    // next line). First match wins; a later Caused-by header must not overwrite the outermost
    // (and most identifying) exception.
    fun noteIfClassNameLine(msg: String) {
        if (exceptionClassName != null) return
        val trimmed = msg.trim()
        if (EXCEPTION_HEADER_RE.matches(trimmed)) exceptionClassName = exceptionClassNameOf(trimmed)
    }

    fun noteFrame(msg: String) {
        val token = FRAME_TOKEN_RE.find(msg.trim())?.groupValues?.get(1) ?: return
        if (firstFrame == null) firstFrame = token
        if (firstNonFrameworkFrame == null && !isFrameworkFrame(token)) firstNonFrameworkFrame = token
    }
}

private const val INT_BITS = 32
private const val LOW_32_BITS_MASK = 0xFFFFFFFFL

// Rule A: packs (pid, tid) into one primitive map key instead of a Pair/data class — this scan
// runs on every computeItems() call over files with millions of lines, so a per-line allocation
// here would be measurable. pid/tid are both Int (32-bit); tid occupies the low 32 bits.
private fun traceKey(pid: Int, tid: Int): Long = (pid.toLong() shl INT_BITS) or (tid.toLong() and LOW_32_BITS_MASK)

// Rule B gate: whether a continuation at logData index `i` is still close enough to `open`'s last
// claimed line to extend it. See MAX_TRACE_INTERLEAVE's doc for the measured justification.
private fun withinTraceReach(open: OpenTrace, i: Long): Boolean = i - open.lastClaimedIdx <= MAX_TRACE_INTERLEAVE

// Decides whether `entry` extends `open` as a continuation member and, if so, mutates `open`'s
// member list / frame / signature state and returns true. Pulled out of computeStackTraceGroups
// for the same reason OpenTrace itself was (see that class's doc comment) — keeping the caller's
// branch count under detekt's CyclomaticComplexMethod threshold.
private fun tryExtendTrace(open: OpenTrace, entry: LogEntry, trigger: Boolean, beforeMember: () -> Unit): Boolean {
    // Real crash dumps put metadata lines — "Process: <pkg>, PID: <n>", the exception class-name
    // line ("java.lang.NullPointerException: ...") — between the "FATAL EXCEPTION" header and the
    // first "at" frame. Tolerate a trigger-like line here too (fold it in) as long as no frame has
    // been seen yet. Once a frame has been seen, a later trigger-like line is a genuinely new
    // exception (back-to-back crashes), not a continuation of this one.
    val isHeaderFollowUp = !open.sawFrame && trigger
    val isUnconditional = isUnconditionalContinuation(entry.msg)
    if (!isUnconditional && !isHeaderFollowUp) return false
    beforeMember()
    open.memberIds.add(entry.id)
    // Rule C: only an unconditional continuation (never a bare isHeaderFollowUp repeat of the
    // trigger shape) proves this is a real stack dump rather than the same one-line message
    // logged repeatedly — see flush()'s doc.
    if (isUnconditional) open.hasUnconditionalMember = true
    if (AT_FRAME_RE.containsMatchIn(entry.msg.trim())) {
        open.sawFrame = true
        open.noteFrame(entry.msg)
    } else if (isHeaderFollowUp) {
        // The class-name line ("java.lang.NullPointerException: ...") lands here for a real
        // "FATAL EXCEPTION" dump — folded in before any frame, same as the doc comment above
        // describes for metadata lines.
        open.noteIfClassNameLine(entry.msg)
    }
    return true
}

/** Primitive member storage avoids a boxed integer for every retained trace line. */
private class IntMembers {
    private var values = IntArray(8)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) {
            check(size < Int.MAX_VALUE) { "Stack trace exceeds supported member count" }
            val capacity = minOf(Int.MAX_VALUE.toLong(), size.toLong() * 2).toInt()
            values = values.copyOf(capacity)
        }
        values[size++] = value
    }

    fun snapshot(): IntArray = values.copyOf(size)
}

/** Immutable primitive representation for a completed stack group. */
internal class CompactStackGroup(
    val rid: Int,
    val isFatal: Boolean,
    val signature: String,
    private val members: IntArray,
) {
    val gid: String get() = "st_$rid"
    val memberCount: Int get() = members.size

    fun memberId(index: Int): Int = members[index]

    fun memberIds(): IntArray = members.copyOf()

    fun toLegacy(): StackTraceGroup = StackTraceGroup(
        gid = gid, rid = rid, memberIds = members.toList(), isFatal = isFatal, signature = signature,
    )
}

/**
 * A single-owner scanner over rows in source order. Open traces, their primitive member IDs and
 * pending preludes can grow with input; the interleave limit bounds gaps, not group length.
 * Callers must discard the scanner on cancellation or source changes; only [finish] publishes.
 */
internal class StackTraceScanner(
    private val beforeMember: () -> Unit = {},
    private val beforeStateAdd: (Long) -> Unit = {},
) {
    private data class Prelude(val ordinal: Long, val id: Int, val tag: String)

    private val openByKey = HashMap<Long, OpenTrace>()
    private val pendingPreludeByKey = HashMap<Long, Prelude>()
    private val groups = mutableListOf<CompactStackGroup>()
    private val scanner = MsgScanner()
    private var ordinal = 0L
    private var finished = false

    private fun flush(key: Long) {
        val open = openByKey.remove(key) ?: return
        if (open.memberIds.size > 0 && open.hasUnconditionalMember) {
            groups += CompactStackGroup(
                rid = open.triggerId,
                isFatal = open.isFatal,
                signature = open.computeSignature(open.triggerId),
                members = open.memberIds.snapshot(),
            )
        }
    }

    fun feed(entry: LogEntry) {
        check(!finished) { "Scanner is finished" }
        check(ordinal < Long.MAX_VALUE) { "Too many source rows" }
        val i = ordinal++
        scanner.scan(entry.msg)
        val trigger = isTrigger(scanner, entry.msg)
        val key = traceKey(entry.pid, entry.tid)
        val open = openByKey[key]
        if (open != null) {
            if (withinTraceReach(open, i) && tryExtendTrace(open, entry, trigger, beforeMember)) {
                open.lastClaimedIdx = i
                return
            }
            flush(key)
        }
        if (trigger) {
            val prelude = pendingPreludeByKey.remove(key)
                ?.takeIf { it.ordinal == i - 1 && it.tag == entry.tag }
            beforeStateAdd(openByKey.size.toLong() + pendingPreludeByKey.size)
            openByKey[key] = OpenTrace(prelude?.id ?: entry.id, prelude?.ordinal ?: i, isFatal = scanner.fatalException)
                .also { trace ->
                    if (prelude != null) {
                        beforeMember()
                        trace.memberIds.add(entry.id)
                        trace.lastClaimedIdx = i
                    }
                    trace.noteIfClassNameLine(entry.msg)
                }
        } else if (isExceptionPrelude(scanner, entry.msg)) {
            if (key !in pendingPreludeByKey) beforeStateAdd(openByKey.size.toLong() + pendingPreludeByKey.size)
            pendingPreludeByKey[key] = Prelude(i, entry.id, entry.tag)
        } else {
            pendingPreludeByKey.remove(key)
        }
    }

    fun finish(cancellationCheck: () -> Unit = {}): List<CompactStackGroup> {
        check(!finished) { "Scanner is finished" }
        finished = true
        val keys = ArrayList<Long>(openByKey.size)
        for (key in openByKey.keys) {
            cancellationCheck()
            keys.add(key)
        }
        for (key in keys) {
            cancellationCheck()
            flush(key)
        }
        // sortedWith is stable; equal root IDs retain the original flush order.
        return groups.sortedWith { left, right ->
            cancellationCheck()
            left.rid.compareTo(right.rid)
        }
    }
}

fun computeStackTraceGroups(logData: List<LogEntry>): List<StackTraceGroup> {
    val scanner = StackTraceScanner()
    logData.forEach(scanner::feed)
    return scanner.finish().map(CompactStackGroup::toLegacy)
}

// EXCEPTION sites are derived 1:1 from computeStackTraceGroups()'s output — the header line an
// exception already collapses onto is exactly what a crash panel needs to jump to.
// ANR sites are a separate single-line scan on "ActivityManager: ANR in ..." lines — real ANR
// dumps continue with Reason:/Load:/CPU usage lines, but v1 only needs the anchor line to jump
// to, not a folded group. Tag/pattern coverage is deliberately narrow (see plan's flagged risk:
// format varies across Android versions/OEMs) — broaden only once validated against real samples.
// NATIVE_CRASH sites are the same kind of single-line anchor scan as ANR: a tombstone dump's
// "Fatal signal N (SIGxxx)..." line on the DEBUG tag. Like ANR, v1 doesn't fold the surrounding
// "***" banner / backtrace frames into a group — flagged as needing validation against real
// native-crash samples before broadening (format/tag can vary across Android versions/OEMs).
fun computeCrashSites(logData: List<LogEntry>, stackGroups: List<StackTraceGroup>): List<CrashSite> {
    // Binary-search view, not a HashMap copy — only a handful of rids ever get looked up.
    val byId = EntryIdMap(logData)
    val exceptionSites = stackGroups.mapNotNull { g ->
        byId[g.rid]?.let { entry ->
            CrashSite(id = "crash_${g.rid}", entry = entry, kind = CrashKind.EXCEPTION, groupGid = g.gid, isFatal = g.isFatal, signature = g.signature)
        }
    }
    val anrSites = logData
        .filter { it.tag == ANR_TAG && ANR_MSG_RE.containsMatchIn(it.msg) }
        .map { CrashSite(id = "crash_${it.id}", entry = it, kind = CrashKind.ANR, groupGid = null, signature = anrSignature(it.msg)) }
    val nativeCrashSites = logData
        .filter { it.tag == NATIVE_CRASH_TAG && NATIVE_CRASH_MSG_RE.containsMatchIn(it.msg) }
        .map {
            CrashSite(
                id = "crash_${it.id}", entry = it, kind = CrashKind.NATIVE_CRASH, groupGid = null, isFatal = true,
                signature = nativeCrashSignature(it.msg),
            )
        }
    val combined = (exceptionSites + anrSites + nativeCrashSites).sortedBy { it.entry.id }
    // Stamps occurrenceCount/firstLogId across the (small — one entry per detected crash line,
    // not per logData line) combined list. Same pass shape as the memoized "handful of rids" cost
    // class computeCrashSites already targets: grouping N crash sites, not the file's M log lines.
    // combined is already sorted by entry.id, so the first site seen per group is the earliest.
    // Keyed by issueGroupKey (not raw signature) so this stamping agrees with groupIssueSites about
    // what a "group" is — see that function's doc comment for why a bare signature isn't enough.
    val occurrenceCounts = combined.groupingBy { issueGroupKey(it) }.eachCount()
    val firstIdByGroupKey = LinkedHashMap<String, Int>()
    combined.forEach { site -> firstIdByGroupKey.putIfAbsent(issueGroupKey(site), site.entry.id) }
    return combined.map { site ->
        val key = issueGroupKey(site)
        site.copy(occurrenceCount = occurrenceCounts.getValue(key), firstLogId = firstIdByGroupKey.getValue(key))
    }
}

// Single source of truth for "what counts as one issue group" — used both to collapse the Issues
// panel's rows (groupIssueSites) and to stamp occurrenceCount/firstLogId in computeCrashSites, so
// the panel and the MCP get_crash_sites contract can never disagree about what a group is. Every
// attribute that decides which category a site lands in (crashSitesForCategory) or how its row is
// labelled (IssueSite.kindLabel/accentColor) must be part of this key — two sites that would render
// differently must never share a group. `kind` is included explicitly rather than relying on the
// signature's "EXC:"/"ANR:"/"NATIVE:" prefix, so a future signature format change can't silently
// re-merge kinds; `isFatal` is what splits EXCEPTION into FATAL_EXCEPTIONS vs EXCEPTIONS and the
// signature never carries it (see CrashCategory's doc and the module comment above on signature
// capture). CustomIssueSite never groups (no signature concept for user-defined rules today), so
// each becomes its own singleton group keyed by its own id.
internal fun issueGroupKey(site: IssueSite): String = when (site) {
    is CrashSite -> "crash:${site.kind}:${site.isFatal}:${site.signature}"
    is CustomIssueSite -> "custom:${site.id}"
}

/** One row in the Issues panel's collapsed-by-signature view: the earliest occurrence plus every
 * occurrence sharing its signature, in document order. Purely a display grouping over the flat
 * `crashSites`/`customIssueSites` lists — the model itself never nests occurrences (see CrashSite's
 * doc comment); every other consumer (minimap, MCP get_crash_sites) keeps reading the flat lists
 * untouched. CustomIssueSite never groups (no signature concept for user-defined rules today), so
 * each becomes its own singleton group.
 */
data class IssueSiteGroup(val representative: IssueSite, val occurrences: List<IssueSite>)

fun groupIssueSites(sites: List<IssueSite>): List<IssueSiteGroup> {
    val order = LinkedHashMap<String, MutableList<IssueSite>>()
    sites.forEach { site ->
        order.getOrPut(issueGroupKey(site)) { mutableListOf() }.add(site)
    }
    // sites is expected to already be in document order (issueSitesForCategory sorts by entry.id),
    // so insertion order into `order` already IS first-occurrence document order; the inner sort
    // only matters for which member becomes `representative` when a signature's occurrences aren't
    // contiguous in the input.
    return order.values.map { members ->
        val ordered = members.sortedBy { it.entry.id }
        IssueSiteGroup(representative = ordered.first(), occurrences = ordered)
    }
}

/**
 * Evaluates Settings-defined issue anchors once during background log analysis. A rule matches
 * either the log tag or message. Invalid patterns
 * are deliberately ignored here: Settings validates drafts before persisting, and a malformed
 * hand-edited cache must not prevent a log from opening.
 */
fun computeCustomIssueSites(logData: List<LogEntry>, rules: List<CustomIssueRule>): List<CustomIssueSite> {
    val enabledRules = rules.asSequence()
        .filter { it.enabled && it.name.isNotBlank() && it.regex.isNotBlank() }
        .filter { rule -> runCatching { Regex(rule.regex) }.isSuccess }
        .toList()
    val regexContext = RegexEvaluationContext()
    return enabledRules.flatMap { rule ->
        logData.asSequence()
            .filter { entry ->
                containsPattern(entry.tag, rule.regex, regex = true, ignoreCase = false, regexContext = regexContext) ||
                    containsPattern(entry.msg, rule.regex, regex = true, ignoreCase = false, regexContext = regexContext)
            }
            .map { entry ->
                CustomIssueSite(
                    id = "custom_issue_${rule.id}_${entry.id}",
                    entry = entry,
                    ruleId = rule.id,
                    categoryName = rule.name,
                )
            }
            .toList()
    }
}

// Selects the crash sites belonging to one crash-panel dropdown category. ALL is the default; the
// next four each narrow to exactly one kind (CRASHES = native, ANRS = ANR) or one EXCEPTION
// subtype (FATAL_EXCEPTIONS / EXCEPTIONS, split by isFatal); OTHERS is whatever (if anything)
// doesn't match any of those four — always empty today, see CrashCategory's doc.
fun crashSitesForCategory(sites: List<CrashSite>, category: CrashCategory): List<CrashSite> = when (category) {
    CrashCategory.ALL -> sites
    CrashCategory.CRASHES -> sites.filter { it.kind == CrashKind.NATIVE_CRASH }
    CrashCategory.ANRS -> sites.filter { it.kind == CrashKind.ANR }
    CrashCategory.FATAL_EXCEPTIONS -> sites.filter { it.kind == CrashKind.EXCEPTION && it.isFatal }
    CrashCategory.EXCEPTIONS -> sites.filter { it.kind == CrashKind.EXCEPTION && !it.isFatal }
    CrashCategory.OTHERS -> sites.filterNot {
        it.kind == CrashKind.NATIVE_CRASH || it.kind == CrashKind.ANR || it.kind == CrashKind.EXCEPTION
    }
}

/** Selects Issues rows for the picker; All keeps one row per log entry and favors built-in crash metadata. */
fun issueSitesForCategory(
    crashSites: List<CrashSite>,
    customSites: List<CustomIssueSite>,
    category: IssueCategorySelection,
): List<IssueSite> = when (category) {
    is IssueCategorySelection.BuiltIn -> when (category.category) {
        CrashCategory.ALL -> (crashSites + customSites)
            .sortedBy { it.entry.id }
            .distinctBy { it.entry.id }
        else -> crashSitesForCategory(crashSites, category.category)
    }
    is IssueCategorySelection.Custom -> customSites.filter { it.ruleId == category.ruleId }
    IssueCategorySelection.DeviceEvents -> emptyList()
}
