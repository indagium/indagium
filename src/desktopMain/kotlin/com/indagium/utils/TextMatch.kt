package com.indagium.utils

import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.displaySeverityText
import java.util.Collections

private data class RegexKey(val pattern: String, val ignoreCase: Boolean)

// (SEC-3) Unbounded ConcurrentHashMap here previously meant every distinct pattern a user or an
// authenticated MCP client (set_filter) ever typed stayed cached for the life of the process — a
// slow but real per-session memory leak on a long-running instance fed a stream of one-off
// patterns. LinkedHashMap in access-order mode + removeEldestEntry gives a plain LRU; wrapping in
// Collections.synchronizedMap keeps every individual get/put call thread-safe against the filter
// hot path calling in from multiple threads, matching the ConcurrentHashMap it replaces. getOrPut
// below (like the ConcurrentHashMap.getOrPut it replaces) is still only get-then-put, not a single
// atomic op — a rare concurrent-miss race recompiles the same pattern twice, which is harmless
// (compiling the same pattern string is deterministic) and was already possible before this change.
private const val REGEX_CACHE_CAPACITY = 256

private val regexCache: MutableMap<RegexKey, Result<Regex>> = Collections.synchronizedMap(
    object : LinkedHashMap<RegexKey, Result<Regex>>(REGEX_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RegexKey, Result<Regex>>): Boolean =
            size > REGEX_CACHE_CAPACITY
    },
)

/** Test-only peephole into cache bounding — the cache itself stays private, only its size leaks. */
internal fun regexCacheSizeForTesting(): Int = regexCache.size

// (SEC-2) User-authored and MCP-authored (set_filter) regex patterns run against arbitrary log
// text on compute threads (and synchronously on the UI thread on some paths). java.util.regex has
// no built-in step/time budget, so a catastrophic-backtracking pattern like "(a+)+$" against a
// long non-matching line can pin a thread indefinitely — the filter's own cancellation check only
// runs between entries, never inside a single Matcher call. DeadlineCharSequence wraps the real
// haystack and throws once a monotonic-clock budget elapses, checked periodically inside charAt() —
// the exact call java.util.regex.Matcher drives per character it inspects, backtracking included.
// 100ms is generous for any legitimate pattern/line combination in this app (interactive filter
// typing already debounces well above that) while bounding the worst case to "one slow frame,"
// never "the thread never comes back."
private const val REGEX_MATCH_BUDGET_MS = 100L
private const val REGEX_MATCH_BUDGET_NANOS = REGEX_MATCH_BUDGET_MS * 1_000_000L
private const val DEADLINE_CHECK_INTERVAL = 1024
internal const val MAX_REGEX_TIMEOUTS_PER_OPERATION = 3

/**
 * Computation-local containment for user-authored regexes.
 *
 * A pattern that times out is skipped for the rest of this one operation, and after a small
 * number of distinct patterns time out all further regex evaluation is skipped. Instances must
 * stay local to one filter/export/render/tool computation: a new operation gets a new instance
 * and retries patterns that timed out previously.
 */
internal class RegexEvaluationContext(
    // Overridden only by deterministic tests; every production operation uses the 100ms default.
    internal val matchBudgetNanos: Long = REGEX_MATCH_BUDGET_NANOS,
) {
    private val timedOutPatterns = HashSet<RegexKey>()
    private var timeoutCount = 0

    internal val timeoutCountForTesting: Int get() = timeoutCount

    internal val hasTimedOut: Boolean get() = timeoutCount > 0

    private fun canEvaluate(key: RegexKey): Boolean =
        timeoutCount < MAX_REGEX_TIMEOUTS_PER_OPERATION && key !in timedOutPatterns

    private fun recordTimeout(key: RegexKey) {
        if (timedOutPatterns.add(key)) timeoutCount++
    }

    internal fun <T> evaluate(
        pattern: String,
        ignoreCase: Boolean,
        timedOutResult: T,
        block: () -> T,
    ): T {
        val key = RegexKey(pattern, ignoreCase)
        if (!canEvaluate(key)) return timedOutResult
        return try {
            block()
        } catch (ignoredTimeout: RegexTimeoutException) {
            recordTimeout(key)
            timedOutResult
        }
    }
}

private class RegexTimeoutException : RuntimeException() {
    // This is a control-flow signal thrown on a hot path, not a real exceptional condition worth
    // paying for a stack trace capture on every catastrophic-backtracking match attempt.
    override fun fillInStackTrace(): Throwable = this
}

// length must stay exact (the regex engine relies on it for bounds/anchors); only charAt (and, for
// safety, subSequence) does the deadline check. deadlineAtNanos is computed once per top-level
// containsPattern/firstRegexMatch/regexRanges call and shared by every charAt during that one
// match/find/findAll pass — NOT reset per character — so the budget bounds the whole operation.
private class DeadlineCharSequence(
    private val real: CharSequence,
    private val deadlineAtNanos: Long,
) : CharSequence {
    private var calls = 0

    override val length: Int get() = real.length

    override fun get(index: Int): Char {
        calls++
        if (calls % DEADLINE_CHECK_INTERVAL == 0 && System.nanoTime() - deadlineAtNanos >= 0L) {
            throw RegexTimeoutException()
        }
        return real[index]
    }

    // Matcher.group()/MatchResult.value slice a small already-matched span via subSequence, not a
    // new pass over the whole haystack — materializing it directly is simplest and doesn't need
    // its own deadline check.
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = real.subSequence(startIndex, endIndex).toString()
}

private fun deadlineWrap(haystack: String, regexContext: RegexEvaluationContext): CharSequence =
    DeadlineCharSequence(haystack, System.nanoTime() + regexContext.matchBudgetNanos)

/** The parser-precomputed profile projection. A non-null result marks a profile-rendered row,
 * including profiles whose selected column list is intentionally empty. */
internal fun profileDisplayColumns(entry: LogEntry): List<LogDisplayField>? =
    entry.recordMetadata?.takeIf { it.usesProfilePresentation }?.displayColumns

internal fun isProfileProcessIdColumn(id: String): Boolean = id == "process_id" || id == "pid"

internal fun isProfileThreadIdColumn(id: String): Boolean = id == "thread_id" || id == "tid"

private val STANDARD_PROFILE_COLUMN_IDS = setOf(
    "timestamp", "severity", "component", "message", "process_id", "thread_id", "pid", "tid", "source", "section",
)

/** Timestamp, level, tag, message, process and thread id: the columns every log line has a place for. */
internal fun isStandardProfileColumn(id: String): Boolean = id in STANDARD_PROFILE_COLUMN_IDS

internal fun isProfileProcessThreadColumn(id: String): Boolean =
    isProfileProcessIdColumn(id) || isProfileThreadIdColumn(id)

internal data class ProfileDisplayTextProjection(
    val text: String,
    val sourceProcessIdRange: Pair<Int, Int>? = null,
    val renderedProcessIdRange: Pair<Int, Int>? = null,
    val sourceSegments: List<ProfileTextOffsetSegment> = emptyList(),
    val renderedFields: List<ProfileRenderedFieldRange> = emptyList(),
)

internal data class ProfileRenderedFieldRange(
    val id: String,
    val start: Int,
    val end: Int,
)

internal data class ProfileTextOffsetSegment(
    val sourceStart: Int,
    val sourceEnd: Int,
    val renderedStart: Int,
    val renderedEnd: Int,
) {
    fun remap(offset: Int): Int = when {
        // Padding has no canonical text: a position at it belongs to what follows the padding.
        sourceStart == sourceEnd -> renderedEnd
        offset <= sourceStart -> renderedStart
        offset >= sourceEnd -> renderedEnd
        sourceEnd - sourceStart == renderedEnd - renderedStart -> renderedStart + offset - sourceStart
        else -> renderedStart
    }
}

internal fun ProfileDisplayTextProjection.remapOffset(offset: Int): Int {
    sourceSegments.firstOrNull { offset <= it.sourceEnd }?.let { return it.remap(offset) }
    return text.length
}

internal fun profileDisplayTextProjection(
    columns: List<LogDisplayField>,
    processDisplayName: String? = null,
    includeProcessThread: Boolean = true,
    // Character width per column id. When given, the rendered text pads each of those columns so
    // rows line up under the header; the canonical text that filters and Find match stays unpadded.
    columnWidths: Map<String, Int>? = null,
    // Drawn under the tab's column header, which names every column: an extra field then shows its
    // value alone. Elsewhere (copy, export, notes, filters) nothing else names it, so it keeps "label=".
    underHeader: Boolean = columnWidths != null,
    shownColumnIds: Set<String>? = null,
): ProfileDisplayTextProjection {
    val text = StringBuilder()
    // Spaces owed before the next rendered column: the short end of the previous value, and the
    // whole width of any empty column in between. Nothing is added after the last column.
    var owedPadding = 0
    val source = StringBuilder()
    var hasSourceColumn = false
    var hasRenderedColumn = false
    var sourceProcessIdRange: Pair<Int, Int>? = null
    var renderedProcessIdRange: Pair<Int, Int>? = null
    val sourceSegments = mutableListOf<ProfileTextOffsetSegment>()
    val renderedFields = mutableListOf<ProfileRenderedFieldRange>()

    fun appendSource(value: String, renderedValue: String?) {
        val sourceStart = source.length
        val renderedStart = text.length
        source.append(value)
        renderedValue?.let(text::append)
        sourceSegments += ProfileTextOffsetSegment(
            sourceStart = sourceStart,
            sourceEnd = source.length,
            renderedStart = renderedStart,
            renderedEnd = text.length,
        )
    }

    columns.forEach { column ->
        val includeColumn = profileColumnShown(column.id, shownColumnIds, includeProcessThread)
        val width = columnWidths?.get(column.id)?.takeIf { includeColumn }
        if (column.value.isBlank()) {
            if (width != null) owedPadding += width + PROFILE_FIELD_SEPARATOR.length
            return@forEach
        }
        if (includeColumn && owedPadding > 0) appendSource("", " ".repeat(owedPadding))
        if (includeColumn) owedPadding = 0
        if (hasSourceColumn) {
            appendSource(PROFILE_FIELD_SEPARATOR, PROFILE_FIELD_SEPARATOR.takeIf { includeColumn && hasRenderedColumn })
        }
        // The standard columns read as a plain log line. An extra field keeps its label in the
        // canonical text, where nothing else says what its value is; under the header it is not drawn.
        val prefix = if (isStandardProfileColumn(column.id)) "" else "${column.label}="
        val sourceFieldStart = source.length
        val sourceValueStart = sourceFieldStart + prefix.length
        val sourceField = prefix + column.value
        hasSourceColumn = true

        if (!includeColumn) {
            appendSource(sourceField, null)
            return@forEach
        }
        val renderedFieldStart = text.length
        if (prefix.isNotEmpty()) appendSource(prefix, prefix.takeUnless { underHeader })
        val renderedValueStart = text.length
        val replacement = processNameReplacement(column, processDisplayName)
        val value = replacement ?: column.value
        val rendered = if (width != null) boundedProfileCell(value, width) else value
        val head = if (replacement == null) truncatedCellHead(value, rendered) else null
        if (head != null) {
            appendSource(head, head)
            appendSource(value.substring(head.length), "…")
        } else {
            appendSource(column.value, rendered)
        }
        if (replacement != null) {
            sourceProcessIdRange = sourceValueStart to sourceValueStart + column.value.length
            renderedProcessIdRange = renderedValueStart to text.length
        }
        renderedFields += ProfileRenderedFieldRange(column.id, renderedFieldStart, text.length)
        hasRenderedColumn = true
        if (width != null) owedPadding = (width - rendered.codePointCount(0, rendered.length)).coerceAtLeast(0)
    }
    return ProfileDisplayTextProjection(
        text = text.toString(),
        sourceProcessIdRange = sourceProcessIdRange,
        renderedProcessIdRange = renderedProcessIdRange,
        sourceSegments = sourceSegments,
        renderedFields = renderedFields,
    )
}

/** Whether a profile column is drawn: listed in [shownColumnIds] (when given), and not a PID/TID column a narrow tile leaves out. */
private fun profileColumnShown(id: String, shownColumnIds: Set<String>?, includeProcessThread: Boolean): Boolean =
    (shownColumnIds == null || id in shownColumnIds) && (includeProcessThread || !isProfileProcessThreadColumn(id))

/** The process name drawn over a PID cell, when one is known and differs from the number. */
private fun processNameReplacement(column: LogDisplayField, processDisplayName: String?): String? =
    processDisplayName?.takeIf { isProfileProcessIdColumn(column.id) && it.isNotBlank() && it != column.value }

/** For a cell cut short with "…", the part of [value] that is still drawn; null when [rendered] is the whole value. */
private fun truncatedCellHead(value: String, rendered: String): String? {
    if (!rendered.endsWith("…")) return null
    val head = rendered.dropLast(1)
    return head.takeIf { value.startsWith(it) }
}

/**
 * Characters before the message column in the rendered row: every earlier column at its padded
 * width, with the separators. A wrapped line hangs here. 0 when the row has no message to indent.
 */
internal fun profileMessageIndentChars(projection: ProfileDisplayTextProjection): Int =
    projection.renderedFields.firstOrNull { it.id == "message" }?.start ?: 0

/**
 * Characters before the second column in the rendered row: the indent a wrapped line falls back to
 * when the message starts too far right for its own indent, or the row has no message. Continuation
 * lines then never start under the first column, where a new row's timestamp would be.
 */
internal fun profileFallbackIndentChars(projection: ProfileDisplayTextProjection): Int =
    projection.renderedFields.getOrNull(1)?.start ?: 0

internal fun profileDisplayText(
    columns: List<LogDisplayField>,
    processDisplayName: String? = null,
    includeProcessThread: Boolean = true,
): String = profileDisplayTextProjection(columns, processDisplayName, includeProcessThread).text

private const val PROFILE_FIELD_SEPARATOR = "  "

/** How many rows a width scan reads between cancellation checks. */
private const val CANCELLATION_CHECK_ROWS = 256
internal const val MAX_PROFILE_COLUMN_CHARS = 40
private const val MIN_MESSAGE_COLUMN_CHARS = 24
private const val MAX_CACHED_PROFILE_LAYOUTS = 16

internal fun boundedProfileCell(text: String, columns: Int): String {
    val flat = if (text.any { it == '\t' || it == '\n' || it == '\r' }) {
        text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    } else {
        text
    }
    if (flat.codePointCount(0, flat.length) <= columns) return flat
    return flat.substring(0, flat.offsetByCodePoints(0, columns - 1)) + "…"
}

internal data class ProfileColumnLayout(
    val columns: List<LogDisplayField>,
    val widths: Map<String, Int>,
) {
    val shownColumnIds = columns.mapTo(hashSetOf()) { it.id }
    val wrapsMessage = columns.lastOrNull()?.id == "message"
    val header = headerFor(true)
    val minimumChars = header.length + if (wrapsMessage) MIN_MESSAGE_COLUMN_CHARS - "MESSAGE".length else 0

    fun headerFor(includeProcessThread: Boolean): String =
        columns.filter { includeProcessThread || !isProfileProcessThreadColumn(it.id) }.joinToString(PROFILE_FIELD_SEPARATOR) { column ->
            val label = boundedProfileCell(profileHeaderLabel(column), MAX_PROFILE_COLUMN_CHARS)
            widths[column.id]?.let(label::padEnd) ?: label
        }.trimEnd()
}

private data class CachedProfileColumns(
    val definitions: List<LogDisplayField>,
    val rows: List<LogEntry>,
    val observedWidths: Map<String, Int>,
    val layout: ProfileColumnLayout,
)

private val profileLayoutCache = object : LinkedHashMap<String, CachedProfileColumns>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedProfileColumns>?): Boolean =
        size > MAX_CACHED_PROFILE_LAYOUTS
}

internal fun profileColumnLayout(
    tabId: String,
    columns: List<LogDisplayField>,
    rows: List<LogEntry>,
    checkCancellation: () -> Unit = {},
): ProfileColumnLayout? {
    if (columns.isEmpty() || rows.firstOrNull()?.recordMetadata?.usesProfilePresentation != true) return null
    val previous = synchronized(profileLayoutCache) { profileLayoutCache[tabId] }?.takeIf { it.definitions == columns }
    if (previous != null && previous.rows === rows) return previous.layout
    val appended = previous?.takeIf { rows.isAppendOnlyExtensionOf(it.rows) }
    val observed = appended?.observedWidths?.toMutableMap() ?: mutableMapOf()
    measureProfileColumns(rows, appended?.rows?.size ?: 0, observed, checkCancellation)
    val layout = layoutProfileColumns(columns, observed)
    synchronized(profileLayoutCache) { profileLayoutCache[tabId] = CachedProfileColumns(columns, rows, observed, layout) }
    return layout
}

internal fun computeProfileColumnLayout(columns: List<LogDisplayField>, rows: List<LogEntry>): ProfileColumnLayout? {
    if (columns.isEmpty() || rows.firstOrNull()?.recordMetadata?.usesProfilePresentation != true) return null
    val observed = mutableMapOf<String, Int>()
    measureProfileColumns(rows, 0, observed) {}
    return layoutProfileColumns(columns, observed)
}

private fun measureProfileColumns(
    rows: List<LogEntry>,
    start: Int,
    widths: MutableMap<String, Int>,
    checkCancellation: () -> Unit,
) {
    for (index in start until rows.size) {
        if (index % CANCELLATION_CHECK_ROWS == 0) checkCancellation()
        rows[index].recordMetadata?.displayColumns?.forEach { column ->
            val previous = widths[column.id] ?: 0
            if (previous < MAX_PROFILE_COLUMN_CHARS && column.value.isNotBlank()) {
                val width = minOf(column.value.length, MAX_PROFILE_COLUMN_CHARS)
                if (width > previous) widths[column.id] = width
            }
        }
    }
}

private fun layoutProfileColumns(columns: List<LogDisplayField>, observed: Map<String, Int>): ProfileColumnLayout {
    val visible = columns.filter { it.id in observed }.ifEmpty { columns }
    val fixed = if (visible.lastOrNull()?.id == "message") visible.dropLast(1) else visible
    return ProfileColumnLayout(visible, fixed.associate { column ->
        column.id to maxOf(profileHeaderLabel(column).length.coerceAtMost(MAX_PROFILE_COLUMN_CHARS), observed[column.id] ?: 0)
    })
}

private fun profileHeaderLabel(column: LogDisplayField): String = when (column.id) {
    "severity" -> "LVL"
    // A profile's components are loggers, units and apps, not Logcat tags: the filter tile says Components too.
    "component" -> "COMPONENT"
    "process_id" -> "PID"
    "thread_id" -> "TID"
    else -> column.label.uppercase()
}

internal fun profileDisplayText(entry: LogEntry): String? =
    profileDisplayColumns(entry)?.let { profileDisplayText(it) }

// This is the exact text presented by LogViewer. Keyword-regex filtering and its visual
// highlight must use one representation so punctuation at field boundaries cannot make a row
// match without highlighting it (or vice versa).
internal fun visibleLogLineText(entry: LogEntry): String = profileDisplayText(entry) ?: buildString {
    append(entry.ts)
    if (entry.pid > 0) {
        append("  ")
        append(entry.pid.toString().padStart(5))
        append(" ")
        append(entry.tid.toString().padStart(5))
    }
    append("  ")
    append(entry.displaySeverityText)
    append("  ")
    append(entry.tag)
    append(": ")
    append(entry.msg)
}

// Distinguishes "syntactically invalid pattern" from "valid pattern, zero matches" — a distinction
// containsPattern/regexRanges deliberately collapse into a single `false`/`emptyList()` result
// (SEC-2/3 above), but ui/SearchBar.kt's find bar wants to show the pattern itself as invalid (e.g.
// red) rather than just "0 matches". Reuses the same cache/key as every other lookup here so
// checking validity never compiles a pattern twice.
internal fun isValidRegexPattern(pattern: String, ignoreCase: Boolean = true): Boolean {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    return regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.isSuccess
}

internal fun containsPattern(
    haystack: String,
    pattern: String,
    regex: Boolean,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Boolean {
    if (!regex) return haystack.contains(pattern, ignoreCase = ignoreCase)
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return false
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = false) {
        compiled.containsMatchIn(deadlineWrap(haystack, regexContext))
    }
}

// Exactly containsPattern("$tag $msg", ...) without materializing the concatenation — a full-file
// keyword filter pass calls this once per entry, and the throwaway concat strings alone were
// ~2GB of GC churn per keystroke on a 10M-line file. Regex patterns still need the real string
// (a match can't be evaluated piecewise), so only the plain-text path avoids the allocation.
internal fun tagMsgContainsPattern(
    tag: String,
    msg: String,
    pattern: String,
    regex: Boolean,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Boolean {
    if (regex) {
        return containsPattern("$tag $msg", pattern, regex = true, ignoreCase = ignoreCase, regexContext = regexContext)
    }
    if (pattern.isEmpty()) return true
    val tagLen = tag.length
    val total = tagLen + 1 + msg.length
    val patLen = pattern.length
    if (patLen > total) return false

    fun charAt(i: Int): Char = when {
        i < tagLen -> tag[i]
        i == tagLen -> ' '
        else -> msg[i - tagLen - 1]
    }

    for (start in 0..(total - patLen)) {
        var k = 0
        while (k < patLen && charAt(start + k).equals(pattern[k], ignoreCase = ignoreCase)) k++
        if (k == patLen) return true
    }
    return false
}

// The substring the pattern actually matched, e.g. for "avc.*denied" against
// "avc: denied : word 1 word 2" this returns "avc: denied" — the useful candidate text,
// as opposed to the whole line or a naive prefix split.
internal fun firstRegexMatch(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): String? {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return null
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = null) {
        compiled.find(deadlineWrap(haystack, regexContext))?.value
    }
}

// Same lookup as firstRegexMatch, but hands back the whole MatchResult (named capture groups
// intact) instead of just the matched substring's text — diagram/SeqDiagramBuilder.kt's RULES
// arrow mode needs the named groups to build a message's endpoints/label from a
// DiagramMessageRule's templates. Kept here rather than duplicated in that package so it shares
// the exact same compiled-regex cache and backtracking-budget wrapping as every other regex entry
// point in the app (SEC-2/SEC-3 above) — internal, not private, so it's visible module-wide: the
// diagram package is a different package in the same module (this project is a single
// jvm("desktop") target), and Kotlin's `internal` is module-scoped, not package-scoped — the same
// cross-package `internal` visibility Filter.kt's CANCELLATION_CHECK_INTERVAL and this file's own
// RegexEvaluationContext already rely on for their `ui`-package callers.
internal fun firstRegexMatchResult(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): MatchResult? {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return null
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = null) {
        compiled.find(deadlineWrap(haystack, regexContext))
    }
}

internal fun regexRanges(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): List<Pair<Int, Int>> {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return emptyList()
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = emptyList()) {
        compiled.findAll(deadlineWrap(haystack, regexContext))
            .map { it.range.first to it.range.last + 1 }
            .toList()
    }
}

// Highlighter variant of regexRanges: same cache/budget wrapping, plus klogg's capture-group mode.
// With [groupsOnly] and a pattern that has capture groups, a match contributes only its groups
// 1..lastCapturedIndex (the highest group that took part), exactly like klogg's
// Highlighter::matchLine — a match where no group took part paints nothing (klogg has no
// whole-match fallback either). A pattern without groups always paints the whole match. Without
// [groupsOnly] this is exactly regexRanges. Empty ranges are never returned (nothing to paint).
internal fun regexHighlightRanges(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean,
    groupsOnly: Boolean,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): List<Pair<Int, Int>> {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return emptyList()
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = emptyList()) {
        buildList {
            for (match in compiled.findAll(deadlineWrap(haystack, regexContext))) {
                val groups = match.groups
                val lastCaptured = (groups.size - 1 downTo 1).firstOrNull { groups[it] != null } ?: 0
                if (!groupsOnly || groups.size == 1) {
                    if (match.range.last >= match.range.first) add(match.range.first to match.range.last + 1)
                } else {
                    for (i in 1..lastCaptured) {
                        val r = groups[i]?.range ?: continue
                        if (r.last >= r.first) add(r.first to r.last + 1)
                    }
                }
            }
        }
    }
}

// klogg's "does this highlighter match the line" for a capture-group highlighter: some match must
// have at least one group that took part (even an empty one) — Highlighter::matchLine returns
// false otherwise, so a whole-line klogg rule like `foo(bar)?|(baz)` does not own a row that only
// has `foo`... unless the pattern has no groups at all, where any match counts.
internal fun regexHighlightHits(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Boolean {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return false
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = false) {
        compiled.findAll(deadlineWrap(haystack, regexContext)).any { m ->
            m.groups.size == 1 || (1 until m.groups.size).any { m.groups[it] != null }
        }
    }
}

// Whole-string match (the pattern must consume all of [text]) through the shared regex cache and
// backtracking budget — AppState uses it to ask "is this selection an exact instance of a
// highlighter's regex" without compiling a throwaway Regex per call.
internal fun regexFullyMatches(
    text: String,
    pattern: String,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Boolean {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return false
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = false) {
        compiled.matches(deadlineWrap(text, regexContext))
    }
}

/** Capture groups from a profile regex through the same cancellation/deadline wrapper as filters. */
internal fun boundedRegexGroups(text: String, pattern: String, fullMatch: Boolean = false): List<String?>? {
    val compiled = regexCache.getOrPut(RegexKey(pattern, false)) { runCatching { Regex(pattern) } }.getOrNull() ?: return null
    val context = RegexEvaluationContext()
    return context.evaluate(pattern, false, timedOutResult = null) {
        val bounded = deadlineWrap(text, context)
        val match = if (fullMatch) compiled.matchEntire(bounded) else compiled.find(bounded)
        match?.groups?.map { it?.value }
    }
}

/** One profile-parse operation shares a single 100ms budget and timeout counter across records. */
internal class BoundedProfileRegexMatcher(pattern: String) {
    private val compiled = regexCache.getOrPut(RegexKey(pattern, false)) { runCatching { Regex(pattern) } }.getOrNull()
    private val context = RegexEvaluationContext()
    val timedOut: Boolean get() = context.hasTimedOut

    fun groups(text: String, fullMatch: Boolean = false): List<String?>? {
        val regex = compiled ?: return null
        return context.evaluate(patternKey, false, timedOutResult = null) {
            val bounded = deadlineWrap(text, context)
            val match = if (fullMatch) regex.matchEntire(bounded) else regex.find(bounded)
            match?.groups?.map { it?.value }
        }
    }

    private val patternKey: String = "profile:${compiled?.pattern.orEmpty()}"
}
