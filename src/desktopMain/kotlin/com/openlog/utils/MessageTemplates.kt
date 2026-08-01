package com.openlog.utils

import com.openlog.model.LogEntry
import com.openlog.model.MessageTemplate
import com.openlog.model.MessageTemplateHistogram
import com.openlog.model.StackTraceGroup
import com.openlog.model.TemplateGranularity
import java.util.BitSet

// ────────────────────────────────────────────────────────────────────────────────────────────────
// LEVEL-INDEPENDENCE IS A DESIGN CONSTRAINT, NOT SOMETHING THIS FILE VERIFIES FOR YOU.
//
// Masking (maskMessage below) runs EXACTLY ONCE per entry, during the scan in
// computeMessageTemplates, and is the SAME function regardless of the granularity the user has
// selected. TemplateGranularity governs ONLY how far the already-masked template is truncated
// (truncateAtSeparator), never what gets masked. This is what makes foldTemplates() a fold over a
// few thousand already-computed map entries — sub-millisecond, no rescan of the file — instead of
// a full re-scan every time the user moves the Loose/Normal/Strict control.
//
// If a future change makes masking depend on the granularity (e.g. "only mask hex at Strict"),
// this identity breaks silently: `fold_k(key(e)) ≡ level_k(e)` stops holding, counts stay
// internally consistent (they still sum) but stop matching what a direct scan at that level would
// have produced — no crash, no exception, just quietly wrong numbers. Example: if Loose masked hex
// and Strict didn't, "alloc 0x1a2b3c4d" would mask to "alloc <hex>" one way and stay
// "alloc 0x<n>a<n>b<n>c<n>d" (digits inside the hex literal masked individually) the other, and
// folding Strict->Loose would not reproduce a direct Loose scan. See
// foldingTheStrictHistogramEqualsScanningDirectlyAtThatGranularity in MessageTemplatesTest — it is
// the executable form of this constraint and the only thing that will catch a future violation.
// ────────────────────────────────────────────────────────────────────────────────────────────────

// ~8MB at 50k entries (tag + template strings + a handful of ints each) — see the module doc
// above computeMessageTemplates for the stop-add policy this bounds.
internal const val MAX_DISTINCT_TEMPLATES = 50_000

// Separators the "Hide/Show messages like this" flyout (AppState.messageRuleVariantsForEntry) and
// the message-template levels below both truncate a message at — the same rough set a human
// skimming logcat output uses to separate a message's stable/templated part from its variable
// tail, e.g. "Card stack expanded: stackId=stack_home" truncates at ':' first ("Card stack
// expanded") then at '=' ("Card stack expanded: stackId"). Promoted from ui/AppState.kt (was
// private there) so this file's level folding and the flyout share one definition.
internal val MESSAGE_RULE_SEPARATORS = charArrayOf('-', '/', '\\', ',', '.', ':', '=')

// Promoted from the local function inside ui/AppState.kt's messageRuleVariantsForEntry (was
// nested, not shared). Truncates at the n-th separator character, trimming trailing whitespace;
// returns [msg] unchanged if it has fewer than [n] separators. Safe to call with an already-masked
// template (INVARIANT below) or a raw message (the flyout's use).
internal fun truncateAtSeparator(msg: String, n: Int): String {
    var count = 0
    for (i in msg.indices) {
        if (msg[i] in MESSAGE_RULE_SEPARATORS) {
            count++
            if (count == n) return msg.substring(0, i).trimEnd()
        }
    }
    return msg
}

private const val HEX_TOKEN = "<hex>"
private const val UUID_TOKEN = "<uuid>"
private const val NUMBER_TOKEN = "<n>"
private const val STRING_TOKEN = "<str>"
private const val PATH_TOKEN = "<path>"

private const val UUID_LENGTH = 36
private const val MIN_HEX_RUN_LENGTH = 12
private const val MAX_QUOTED_STRING_CONTENT = 128
private const val MIN_PATH_SLASHES = 2

private fun isHexDigitChar(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

private fun isAlnum(c: Char) = c.isLetterOrDigit()

/** One masked message. See the module header for why [literalPrefixLength]/[literalPrefixRawLength]
 *  must come from the scan itself rather than being recovered by parsing [template] afterwards. */
internal data class MaskResult(
    val template: String,
    val literalPrefixLength: Int,
    val literalPrefixRawLength: Int,
)

// Single left-to-right character scan producing a masked template, structured like
// StackTraceComputer.kt's MsgScanner: one pass, every rule gated on a cheap character test before
// any work, reused across many calls via one instance per computeMessageTemplates() scan instead
// of allocating a scanner (or StringBuilder) per line.
//
// INVARIANT (asserted in MessageTemplatesTest): no mask token contains a separator character
// (MESSAGE_RULE_SEPARATORS) — every rule below replaces its ENTIRE matched raw run with one fixed
// token string in a single append, never leaving a partial separator behind, so truncateAtSeparator
// can never cut inside a token.
internal class MessageMasker {
    private val sb = StringBuilder()

    /** The masked content after a [scan] call that set [transformed] to true. Exposed so a
     *  hot-path caller (computeMessageTemplates) can compare its own candidates against this
     *  directly — via [CharSequence.contentEquals], no allocation — instead of paying for the
     *  sb.toString() [mask] does. Valid only until the next scan()/mask() call reuses the buffer;
     *  the scan loop in computeMessageTemplates always finishes with one entry's content (hit,
     *  or a genuine miss that materializes the String) before touching the next, so this is safe. */
    val builder: CharSequence get() = sb

    // [scan]'s results, as reused instance fields rather than a returned wrapper object: a
    // wrapper class allocates on EVERY scan() call, hit or miss, which is worse than the
    // sb.toString() this was meant to avoid — that only allocated on transformed lines, a wrapper
    // would allocate on all of them (measured: made the 10M-line scan slower, not faster). Same
    // "one instance, reused across many calls" shape as `sb` above. Read these immediately after
    // calling [scan] and before the next scan()/mask() call overwrites them.
    var transformed: Boolean = false
        private set
    var literalPrefixLength: Int = 0
        private set
    var literalPrefixRawLength: Int = 0
        private set

    fun mask(raw: String): MaskResult {
        scan(raw)
        val template = if (transformed) sb.toString() else raw
        return MaskResult(template, literalPrefixLength, literalPrefixRawLength)
    }

    // Branchy by design, same justification as StackTraceComputer.kt's MsgScanner: one dispatch
    // per character class IS the optimization here — each `when` branch is a cheap character test
    // gating real work, and splitting the branches into separate passes would reintroduce the
    // multiple-scan cost this class exists to avoid.
    //
    // NOTE for anyone tempted to add a per-line hash here: it was tried (a custom FNV-1a pass
    // over the masked content, feeding an open-addressed hash table in computeMessageTemplates)
    // and measured SLOWER end-to-end than materializing + java.util.HashMap, on this exact
    // 10M-line fixture. Isolated with a hash-only-vs-disabled A/B on the real harness: removing
    // just the hash loop and nothing else took computeMessageTemplates from ~630ms back to
    // ~470ms at 1M lines (matching the pre-change baseline exactly) — the loop itself was the
    // entire cost, not incidental to it. Root cause: recent JDKs vectorize/intrinsify
    // String.hashCode() and the bulk copy inside String(char[]) (what sb.toString() and
    // HashMap's internal hashing already use for free); a hand-rolled Kotlin reduction loop over
    // the same characters gets none of that and costs more per character than what it replaces,
    // regardless of hash width (Long FNV and Int/31-multiplier were both measured, both slower).
    // computeMessageTemplates' TemplateTable instead avoids allocation a different way: by
    // exploiting this workload's actual shape (a handful of distinct templates per tag) with a
    // linear scan, not a hash — see that class's doc.
    @Suppress("CyclomaticComplexMethod")
    fun scan(raw: String) {
        sb.setLength(0)
        val n = raw.length
        var i = 0
        var pendingStart = 0
        var localLiteralPrefixLength = -1
        var localLiteralPrefixRawLength = -1
        // Whether this message actually differs from `raw`. NOT the same as "sb is non-empty":
        // flushLiteral copies untouched text into sb too, so an entirely unmasked message still
        // fills it. Tracking the transformation itself is what lets the common case return `raw`
        // by identity instead of paying for a toString() on every one of ~10M lines.
        var localTransformed = false

        fun flushLiteral(uptoRaw: Int) {
            if (uptoRaw > pendingStart) sb.append(raw, pendingStart, uptoRaw)
        }

        fun emitToken(matchStart: Int, matchEnd: Int, token: String) {
            localTransformed = true
            flushLiteral(matchStart)
            if (localLiteralPrefixLength < 0) {
                localLiteralPrefixLength = sb.length
                localLiteralPrefixRawLength = matchStart
            }
            sb.append(token)
            pendingStart = matchEnd
            i = matchEnd
        }

        while (i < n) {
            val c = raw[i]
            when {
                c == '0' && i + 1 < n && (raw[i + 1] == 'x' || raw[i + 1] == 'X') -> {
                    val end = scanHex0x(raw, i)
                    if (end != null) emitToken(i, end, HEX_TOKEN) else i++
                }

                isHexDigitChar(c) && isTokenStart(raw, i) -> {
                    val uuidEnd = matchUuid(raw, i)
                    when {
                        uuidEnd != null -> emitToken(i, uuidEnd, UUID_TOKEN)
                        else -> {
                            val hexEnd = matchHexRun(raw, i)
                            if (hexEnd != null) {
                                emitToken(i, hexEnd, HEX_TOKEN)
                            } else if (c.isDigit() && isDigitGroupStart(raw, i)) {
                                emitToken(i, matchDigitGroups(raw, i), NUMBER_TOKEN)
                            } else {
                                i++
                            }
                        }
                    }
                }

                c.isDigit() && isDigitGroupStart(raw, i) -> emitToken(i, matchDigitGroups(raw, i), NUMBER_TOKEN)

                c == '"' -> {
                    val end = matchQuotedString(raw, i)
                    if (end != null) emitToken(i, end, STRING_TOKEN) else i++
                }

                c == '/' && isPathStart(raw, i) -> {
                    val end = matchPathRun(raw, i)
                    if (end != null) emitToken(i, end, PATH_TOKEN) else i++
                }

                c.isWhitespace() -> {
                    var j = i
                    while (j < n && raw[j].isWhitespace()) j++
                    if (j - i > 1) {
                        localTransformed = true
                        flushLiteral(i)
                        sb.append(' ')
                        pendingStart = j
                        i = j
                    } else {
                        i++
                    }
                }

                else -> i++
            }
        }
        if (localTransformed) flushLiteral(n)

        transformed = localTransformed
        if (!localTransformed) {
            literalPrefixLength = n
            literalPrefixRawLength = n
        } else if (localLiteralPrefixLength < 0) {
            literalPrefixLength = sb.length
            literalPrefixRawLength = n
        } else {
            literalPrefixLength = localLiteralPrefixLength
            literalPrefixRawLength = localLiteralPrefixRawLength
        }
    }
}

// A hex digit at [i] only starts a new UUID/hex-run scan if it isn't itself mid an existing
// alnum run — otherwise "abc0123456789def" would spuriously start a hex-run scan at every digit
// inside it instead of once at 'a'.
private fun isTokenStart(raw: String, i: Int): Boolean = i == 0 || !isAlnum(raw[i - 1])

// THE DIGIT-AFTER-LETTER GUARD lives here: a digit only starts a new digit-group scan if it isn't
// itself mid an existing digit run (so a run rejected below is skipped as a whole instead of being
// re-tested one digit at a time starting from its second character — see "H264") AND the character
// immediately before it isn't an ASCII letter (so "Camera2"/"H264"/"sha256"/"utf8"/"MD5" survive
// intact while "id=42"/"pid 1234"/"stack_home_2" still mask, since '=', ' ', '_' aren't letters).
private fun isDigitGroupStart(raw: String, i: Int): Boolean {
    if (i == 0) return true
    val prev = raw[i - 1]
    return !prev.isDigit() && !isAsciiLetter(prev)
}

private fun scanHex0x(raw: String, i: Int): Int? {
    var j = i + 2
    if (j >= raw.length || !isHexDigitChar(raw[j])) return null
    while (j < raw.length && isHexDigitChar(raw[j])) j++
    return j
}

// The four dash positions in the canonical 8-4-4-4-12 UUID layout. Every other offset must be a
// hex digit; see matchUuid.
private val UUID_DASH_OFFSETS = intArrayOf(8, 13, 18, 23)

private fun matchUuid(raw: String, i: Int): Int? {
    if (i + UUID_LENGTH > raw.length) return null
    for (k in 0 until UUID_LENGTH) {
        val ch = raw[i + k]
        val isDashSlot = k in UUID_DASH_OFFSETS
        if (isDashSlot) {
            if (ch != '-') return null
        } else if (!isHexDigitChar(ch)) {
            return null
        }
    }
    val after = i + UUID_LENGTH
    if (after < raw.length && isAlnum(raw[after])) return null
    return after
}

// This is only reached when isTokenStart(raw, i) already held, so an alnum run always starts fresh
// at a non-alnum boundary here (no separate run-start check needed). Requires >=1 hex LETTER
// (a-fA-F) in the run so a purely numeric run (no letters) falls through to the digit-group rule
// instead — a 13-digit epoch timestamp becomes <n>, not <hex>.
private fun matchHexRun(raw: String, i: Int): Int? {
    var j = i
    var hasLetter = false
    var allHex = true
    while (j < raw.length && isAlnum(raw[j])) {
        val ch = raw[j]
        if (!isHexDigitChar(ch)) allHex = false
        if (ch in 'a'..'f' || ch in 'A'..'F') hasLetter = true
        j++
    }
    if (!allHex || !hasLetter || j - i < MIN_HEX_RUN_LENGTH) return null
    return j
}

// The letter-predecessor guard is enforced by callers (isDigitGroupStart) before this is invoked;
// this just consumes the maximal run once a valid start position has already been confirmed.
private fun matchDigitGroups(raw: String, i: Int): Int {
    var j = i
    while (j < raw.length && raw[j].isDigit()) j++
    while (j < raw.length && raw[j] == '.' && j + 1 < raw.length && raw[j + 1].isDigit()) {
        j++
        while (j < raw.length && raw[j].isDigit()) j++
    }
    return j
}

private fun matchQuotedString(raw: String, i: Int): Int? {
    var j = i + 1
    while (j < raw.length) {
        val ch = raw[j]
        if (ch == '\n') return null
        if (ch == '"') {
            val contentLen = j - (i + 1)
            return if (contentLen <= MAX_QUOTED_STRING_CONTENT) j + 1 else null
        }
        j++
    }
    return null
}

// Deliberately excludes ':' from the allowed preceding characters — otherwise
// "https://api.example.com/v2/users" would collapse to "https:<path>", far more aggressive than
// "composition" implies. A URL's slashes stay literal; only its digits mask (matchDigitGroups
// still fires on e.g. the "12345" in ".../users/12345").
private fun isPathStart(raw: String, i: Int): Boolean =
    i == 0 || raw[i - 1].isWhitespace() || raw[i - 1] == '=' || raw[i - 1] == '(' || raw[i - 1] == '['

private fun matchPathRun(raw: String, i: Int): Int? {
    var j = i
    var slashes = 0
    while (j < raw.length && !raw[j].isWhitespace()) {
        if (raw[j] == '/') slashes++
        j++
    }
    return if (slashes >= MIN_PATH_SLASHES) j else null
}

/** Convenience single-call entry point (tests, and any one-off caller). The hot scan path in
 *  [computeMessageTemplates] uses its own reused [MessageMasker] instance instead. */
internal fun maskMessage(raw: String): MaskResult = MessageMasker().mask(raw)

// Mutable accumulator for one (tag, template) bucket during a scan — flattened to the immutable
// MessageTemplate list only once, at the end (see the class doc on computeMessageTemplates).
private class TemplateAcc(
    var count: Int,
    var firstEntryId: Int,
    var lastEntryId: Int,
    val literalPrefixLength: Int,
    val literalPrefixRawLength: Int,
) {
    fun accept(entryId: Int) {
        count++
        if (entryId < firstEntryId) firstEntryId = entryId
        if (entryId > lastEntryId) lastEntryId = entryId
    }
}

private fun stackMemberBits(stackTraceGroups: List<StackTraceGroup>): BitSet {
    val bits = BitSet()
    for (i in stackTraceGroups.indices) {
        val members = stackTraceGroups[i].memberIds
        for (j in members.indices) bits.set(members[j])
    }
    return bits
}

private fun flattenAndSort(byTag: HashMap<String, HashMap<String, TemplateAcc>>): List<MessageTemplate> {
    val out = ArrayList<MessageTemplate>()
    for ((tag, templates) in byTag) {
        for ((template, acc) in templates) {
            out += MessageTemplate(
                tag = tag,
                template = template,
                count = acc.count,
                firstEntryId = acc.firstEntryId,
                lastEntryId = acc.lastEntryId,
                literalPrefixLength = acc.literalPrefixLength,
                literalPrefixRawLength = acc.literalPrefixRawLength,
            )
        }
    }
    out.sortWith(compareByDescending<MessageTemplate> { it.count }.thenBy { it.firstEntryId })
    return out
}

// Below this many distinct templates for one tag, TemplateTable stays a flat linear-scan list;
// above it, a genuinely pathological tag (near-unique messages) would make a per-line linear scan
// itself the bottleneck, so it converts to a java.util.HashMap instead — seen in practice on
// pathological files, not the common case this table is optimized for (see the class doc).
private const val TEMPLATE_TABLE_LINEAR_SCAN_LIMIT = 32

// Per-tag lookup structure for computeMessageTemplates' hot loop. Avoids the sb.toString()
// allocation on every one of ~10M lines — even though a realistic file collapses to a handful of
// distinct (tag, template) pairs (14 on the 10M-line fixture this was profiled against) — by
// keeping the small set of already-seen templates as Strings and comparing the scanner's live
// content (its StringBuilder, or the raw String when nothing was masked) against them directly
// via [CharSequence.contentEquals]: no String has to exist just to do the lookup. A String key is
// built by the caller only on a genuine miss.
//
// This is a LINEAR SCAN, not a hash table — that's deliberate, not a missing optimization. A
// custom-hash open-addressed table was built and measured first; it was consistently SLOWER than
// materializing a String and using java.util.HashMap (see MessageMasker.scan's doc for the
// isolated A/B that pinned this down to the hash loop itself). java.util.HashMap.get() gets a
// JIT-vectorized String.hashCode() and String.equals() for free; a hand-rolled per-character hash
// loop gets none of that and, on this JVM, costs more per character than what it replaces. A
// linear scan sidesteps the question entirely: for the low distinct-count this workload actually
// has, comparing against every known candidate is cheaper than computing any hash at all, and
// [find] moves a hit to the front of the list so a bursty run of the same repeated template (the
// common shape of real log output) costs one comparison, not a scan of everything seen so far.
private class TemplateTable {
    private val keys = ArrayList<String>()
    private val accs = ArrayList<TemplateAcc>()
    private var overflow: HashMap<String, TemplateAcc>? = null

    /** Returns the existing template's accumulator on a hit — no allocation was needed to find
     *  it, in the (expected) linear-scan regime. Null on a miss; the caller must materialize
     *  [content] into a String and call [insert]. Above [TEMPLATE_TABLE_LINEAR_SCAN_LIMIT] this
     *  has already converted to a HashMap and must materialize [content] itself to probe it —
     *  identical cost to the pre-this-change baseline, so the pathological case is never worse
     *  than before, just no longer better either. */
    fun find(content: CharSequence): TemplateAcc? {
        val map = overflow
        if (map != null) return map[content.toString()]
        for (idx in keys.indices) {
            if (keys[idx].contentEquals(content)) {
                if (idx != 0) moveToFront(idx)
                return accs[0]
            }
        }
        return null
    }

    private fun moveToFront(idx: Int) {
        val key = keys.removeAt(idx)
        val acc = accs.removeAt(idx)
        keys.add(0, key)
        accs.add(0, acc)
    }

    /** Inserts a genuinely new key. Caller must have already confirmed via [find] that no
     *  matching entry exists. */
    fun insert(key: String, acc: TemplateAcc) {
        val map = overflow
        if (map != null) {
            map[key] = acc
            return
        }
        keys.add(0, key)
        accs.add(0, acc)
        if (keys.size > TEMPLATE_TABLE_LINEAR_SCAN_LIMIT) {
            val newMap = HashMap<String, TemplateAcc>(keys.size * 2)
            for (i in keys.indices) newMap[keys[i]] = accs[i]
            overflow = newMap
            keys.clear()
            accs.clear()
        }
    }

    inline fun forEach(action: (template: String, acc: TemplateAcc) -> Unit) {
        val map = overflow
        if (map != null) {
            for ((k, v) in map) action(k, v)
        } else {
            for (idx in keys.indices) action(keys[idx], accs[idx])
        }
    }
}

private fun flattenAndSortTables(byTag: HashMap<String, TemplateTable>): List<MessageTemplate> {
    val out = ArrayList<MessageTemplate>()
    for ((tag, table) in byTag) {
        table.forEach { template, acc ->
            out += MessageTemplate(
                tag = tag,
                template = template,
                count = acc.count,
                firstEntryId = acc.firstEntryId,
                lastEntryId = acc.lastEntryId,
                literalPrefixLength = acc.literalPrefixLength,
                literalPrefixRawLength = acc.literalPrefixRawLength,
            )
        }
    }
    out.sortWith(compareByDescending<MessageTemplate> { it.count }.thenBy { it.firstEntryId })
    return out
}

// Computes the global message-composition histogram: how many entries share each (tag, masked
// message) template, ranked both ways (most-frequent = noise to hide, rarest = what to look at).
// Always scans at STRICT (untruncated) granularity — callers wanting a coarser view call
// foldTemplates() on the result rather than re-scanning (see the module header).
//
// Stack-trace members are excluded from counting (not from [totalEntries]): a single crash dump
// contributes 40-200 near-unique "at com.foo.Bar.baz(Bar.java:123)" frame lines that would blow
// the distinct-template cap on frames alone and drown out everything else in the histogram. The
// trigger line stays — it identifies the crash and Issues already surfaces it separately.
//
// Cap policy is stop-add: once MAX_DISTINCT_TEMPLATES distinct (tag, template) keys exist,
// existing keys keep incrementing but a genuinely new key is dropped (counted only in
// totalEntries) and [MessageTemplateHistogram.overflowed] is set. This is unsound specifically for
// the rare/"what to look at" lens: a one-off line that first appears late in a pathological file
// can be dropped before it ever gets its own bucket. Stage 2's UI must warn about this rather than
// imply the rare-lens view is complete.
internal fun computeMessageTemplates(
    logData: List<LogEntry>,
    stackTraceGroups: List<StackTraceGroup> = emptyList(),
    cap: Int = MAX_DISTINCT_TEMPLATES,
): MessageTemplateHistogram {
    val stackMemberIds = stackMemberBits(stackTraceGroups)
    val byTag = HashMap<String, TemplateTable>()
    val masker = MessageMasker()
    var countedEntries = 0
    var distinctCount = 0
    var overflowed = false

    for (i in logData.indices) {
        val entry = logData[i]
        if (stackMemberIds.get(entry.id)) continue
        // The allocation this scan used to pay ~once per line (sb.toString() inside mask(), even
        // though a realistic file collapses to a handful of distinct templates — see the module
        // header): scan() leaves the masked characters in masker.builder; TemplateTable.find()
        // compares against them directly with no String involved. A String is built below only
        // on a genuine miss (distinctCount, bounded by cap) — see TemplateTable's doc for why
        // this is a linear scan rather than a hash table.
        masker.scan(entry.msg)
        val content: CharSequence = if (masker.transformed) masker.builder else entry.msg
        val table = byTag.getOrPut(entry.tag) { TemplateTable() }
        val existing = table.find(content)
        if (existing != null) {
            existing.accept(entry.id)
            countedEntries++
        } else if (distinctCount < cap) {
            table.insert(
                content.toString(),
                TemplateAcc(
                    count = 1,
                    firstEntryId = entry.id,
                    lastEntryId = entry.id,
                    literalPrefixLength = masker.literalPrefixLength,
                    literalPrefixRawLength = masker.literalPrefixRawLength,
                ),
            )
            distinctCount++
            countedEntries++
        } else {
            overflowed = true
        }
    }

    return MessageTemplateHistogram(
        templates = flattenAndSortTables(byTag),
        granularity = TemplateGranularity.STRICT,
        totalEntries = logData.size,
        countedEntries = countedEntries,
        overflowed = overflowed,
    )
}

// Incremental merge for tailing (ui/TailCoordinator.kt): [incoming] is a fresh
// computeMessageTemplates() scan of ONLY the newly-tailed batch (masking depends solely on the
// individual line, so this union is exact for counts/bounds). The batch's own stack-trace-group
// exclusion only sees that batch, so a trace straddling the batch boundary loses exclusion for its
// tail until the debounced full buildLogAnalysis() (ui/AppState.kt) replaces this merged result
// wholesale — bounded (one batch's worth of near-unique frame lines at most) and self-healing.
internal fun mergeMessageTemplates(
    existing: MessageTemplateHistogram,
    incoming: MessageTemplateHistogram,
    cap: Int = MAX_DISTINCT_TEMPLATES,
): MessageTemplateHistogram {
    val byTag = HashMap<String, HashMap<String, TemplateAcc>>()
    for (t in existing.templates) {
        byTag.getOrPut(t.tag) { HashMap() }[t.template] =
            TemplateAcc(t.count, t.firstEntryId, t.lastEntryId, t.literalPrefixLength, t.literalPrefixRawLength)
    }
    var distinctCount = existing.templates.size
    var countedEntries = existing.countedEntries
    var overflowed = existing.overflowed || incoming.overflowed
    for (t in incoming.templates) {
        val templates = byTag.getOrPut(t.tag) { HashMap() }
        val current = templates[t.template]
        if (current != null) {
            current.count += t.count
            if (t.firstEntryId < current.firstEntryId) current.firstEntryId = t.firstEntryId
            if (t.lastEntryId > current.lastEntryId) current.lastEntryId = t.lastEntryId
            countedEntries += t.count
        } else if (distinctCount < cap) {
            templates[t.template] = TemplateAcc(t.count, t.firstEntryId, t.lastEntryId, t.literalPrefixLength, t.literalPrefixRawLength)
            distinctCount++
            countedEntries += t.count
        } else {
            overflowed = true
        }
    }
    return MessageTemplateHistogram(
        templates = flattenAndSort(byTag),
        granularity = TemplateGranularity.STRICT,
        totalEntries = existing.totalEntries + incoming.totalEntries,
        countedEntries = countedEntries,
        overflowed = overflowed,
    )
}

private fun truncationCountFor(granularity: TemplateGranularity): Int? = when (granularity) {
    TemplateGranularity.STRICT -> null
    TemplateGranularity.NORMAL -> 2
    TemplateGranularity.LOOSE -> 1
}

// Folds an already-computed (always-STRICT) histogram down to a coarser granularity by truncating
// each stored template — never by rescanning logData. This is what makes moving the Loose/Normal/
// Strict control sub-millisecond: a fold over a few thousand map entries instead of a rescan of
// the whole file. See the module header for the constraint this depends on, and
// foldingTheStrictHistogramEqualsScanningDirectlyAtThatGranularity (MessageTemplatesTest) for the
// executable proof that this fold matches a direct scan at the target level.
internal fun foldTemplates(histogram: MessageTemplateHistogram, granularity: TemplateGranularity): List<MessageTemplate> {
    if (histogram.granularity == granularity) return histogram.templates
    // Folding "up" to Strict from an already-truncated stored histogram can't recover the
    // untruncated template — not a supported path (the stored histogram is always STRICT in
    // Stage 1; this branch only guards against a future caller storing a truncated histogram).
    // Falls back to the stored (truncated) templates rather than fabricating data.
    val n = truncationCountFor(granularity) ?: return histogram.templates

    val folded = LinkedHashMap<Pair<String, String>, TemplateAcc>()
    for (t in histogram.templates) {
        val truncated = truncateAtSeparator(t.template, n)
        val key = t.tag to truncated
        val existing = folded[key]
        if (existing == null) {
            folded[key] = TemplateAcc(
                count = t.count,
                firstEntryId = t.firstEntryId,
                lastEntryId = t.lastEntryId,
                literalPrefixLength = minOf(t.literalPrefixLength, truncated.length),
                literalPrefixRawLength = t.literalPrefixRawLength,
            )
        } else {
            existing.count += t.count
            if (t.firstEntryId < existing.firstEntryId) existing.firstEntryId = t.firstEntryId
            if (t.lastEntryId > existing.lastEntryId) existing.lastEntryId = t.lastEntryId
        }
    }
    val out = ArrayList<MessageTemplate>(folded.size)
    for ((key, acc) in folded) {
        out += MessageTemplate(
            tag = key.first,
            template = key.second,
            count = acc.count,
            firstEntryId = acc.firstEntryId,
            lastEntryId = acc.lastEntryId,
            literalPrefixLength = acc.literalPrefixLength,
            literalPrefixRawLength = acc.literalPrefixRawLength,
        )
    }
    out.sortWith(compareByDescending<MessageTemplate> { it.count }.thenBy { it.firstEntryId })
    return out
}
