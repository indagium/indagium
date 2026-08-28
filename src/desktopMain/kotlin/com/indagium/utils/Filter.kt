package com.indagium.utils

import androidx.compose.ui.graphics.Color
import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.ParsedSeq3
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.*
import com.indagium.ui.DANGER_RED
import com.indagium.ui.SEQ_COLORS

// Tags-mode message/PID rules and the kwInTag live-search add matches on top of the base tag
// filter rather than replacing it — an entry passes if it satisfies a positive selector OR the
// base tag filter. Regex/Keyword mode is intentionally just kwText + kwRegex; persisted
// KEYWORD-mode rules are ignored so hidden rules cannot silently affect results.
// Negative rules and exclusions apply only when their owning feature is active.

/**
 * The part of a [Filter] that actually decides which entries are admitted — i.e. everything
 * [passesFilter] reads. Highlighters, the keyword-highlight settings, and sequence folding are
 * display concerns: they change how surviving rows LOOK, never which rows survive.
 *
 * Callers cache work keyed on "the current view" (the log-composition scan is the first) and must
 * not redo it when the user merely recolours something. Adding a highlighter changes `Filter` and
 * so would invalidate such a cache, which is why comparing whole Filters is wrong for that purpose.
 *
 * Deliberately written as a copy that BLANKS the display-only fields rather than as a projection
 * listing the filtering ones: a field added to [Filter] later is then included by default, so the
 * failure mode of forgetting to update this is an unnecessary recompute rather than a silently
 * stale one. If a field added here turns out to be display-only, blank it explicitly.
 */
fun Filter.viewDefiningKey(): Filter = copy(
    highlighters = emptyList(),
    kwHighlightEnabled = false,
    kwHighlightColor = DEFAULT_KEYWORD_HIGHLIGHT_COLOR,
    seqOn = true,
    sequences = emptyList(),
)

fun passesFilter(entry: LogEntry, filter: Filter): Boolean =
    passesFilter(entry, filter, RegexEvaluationContext())

internal fun passesFilter(entry: LogEntry, filter: Filter, regexContext: RegexEvaluationContext): Boolean =
    passesFilter(entry, filter, emptyMap(), regexContext)

// processNames resolves pid -> process name for PID/TID selectors that accept a package name
// instead of a bare number (pidTidFilter and RuleTarget.PID_TID message rules — see
// resolvePidTidTokens). Defaults to emptyMap() so every pre-existing 2-/3-arg caller (the whole
// test suite, FilterPanel's candidate scans that already zero out pidTidFilter) keeps exactly its
// prior behavior; only visibleEntries(tab, ...) — the one caller with a LogAnalysis to read the
// map from — passes a populated one.
internal fun passesFilter(
    entry: LogEntry,
    filter: Filter,
    processNames: Map<Int, String>,
    regexContext: RegexEvaluationContext,
): Boolean {
    val enabledRules = if (filter.mode == FilterMode.TAGS) {
        filter.messageRules.filter { it.enabled && it.pattern.isNotBlank() && it.mode == FilterMode.TAGS }
    } else {
        emptyList()
    }
    if (!passesExclusions(entry, filter, enabledRules.filter { !it.include }, processNames, regexContext)) return false
    val posRules = enabledRules.filter { it.include }
    val hasKwInTag = filter.mode == FilterMode.TAGS && filter.kwInTag.isNotBlank()
    val hasPosPidTid = filter.pidTidFilter.isNotBlank()
    if (posRules.isNotEmpty() || hasKwInTag || hasPosPidTid) {
        return matchesPositiveSelectors(entry, posRules, hasKwInTag, hasPosPidTid, filter, processNames, regexContext)
    }
    return passesTagOrKeywordFilter(entry, filter, regexContext)
}

private fun passesExclusions(
    entry: LogEntry,
    filter: Filter,
    negativeRules: List<MessageRule>,
    processNames: Map<Int, String>,
    regexContext: RegexEvaluationContext,
): Boolean {
    if (entry.level !in filter.levels) return false
    // Tag/package exclusion is a Tags-mode-flavored concept — kept out of Regex/Keyword mode so
    // it can't silently narrow results there, matching the same independence as message rules.
    if (filter.mode == FilterMode.TAGS) {
        if (entry.tag in filter.excludeTags) return false
        if (filter.excludePkgPrefixes.any { pfx -> tagMatchesPrefix(entry.tag, pfx) }) return false
    }
    if (filter.excludeKw.isNotBlank() &&
        tagMsgContainsPattern(entry.tag, entry.msg, filter.excludeKw, filter.excludeKwRegex, regexContext = regexContext)) return false
    return negativeRules.none { rule -> ruleScopeMatches(entry, rule) && matchesRule(entry, rule, processNames, regexContext) }
}

private fun matchesPositiveSelectors(
    entry: LogEntry,
    posRules: List<MessageRule>,
    hasKwInTag: Boolean,
    hasPosPidTid: Boolean,
    filter: Filter,
    processNames: Map<Int, String>,
    regexContext: RegexEvaluationContext,
): Boolean {
    // ruleScopeMatches is a no-op (always true) for unscoped rules, so this covers both.
    if (posRules.any { rule -> ruleScopeMatches(entry, rule) && matchesRule(entry, rule, processNames, regexContext) }) return true
    if (hasKwInTag && containsPattern(entry.msg, filter.kwInTag, filter.kwInTagRegex, regexContext = regexContext)) return true
    if (hasPosPidTid && matchesPidTidFilter(entry, filter.pidTidFilter, processNames)) return true
    return hasActiveBaseFilter(filter) && passesTagOrKeywordFilter(entry, filter, regexContext)
}

private fun hasActiveBaseFilter(filter: Filter): Boolean = when (filter.mode) {
    FilterMode.TAGS -> filter.activeTags.isNotEmpty() || filter.pkgPrefixes.isNotEmpty()
    FilterMode.KEYWORD -> filter.kwText.isNotBlank()
}

// A pid/tid selector token is either numeric (matched against BOTH entry.pid and entry.tid, the
// pre-existing behavior — a raw number could mean either) or a process name, resolved via
// LogAnalysis.processNames and matched only against entry.pid: a name can't collide with an
// unrelated tid the way two small integers coincidentally can. Shared by pidTidFilter
// (matchesPidTidFilter, below) and MessageRule's PID_TID target (matchesRule's own branch, and
// ui/FilterPanel.kt's relevantScopeTags candidate scan) so the two UI entry points — the filter
// itself and the scope-tag picker for a pending PID_TID rule — can never resolve a name
// differently from each other.
internal data class PidTidTokens(val rawTokens: Set<String>, val namedPids: Set<Int>)

internal fun resolvePidTidTokens(pattern: String, processNames: Map<Int, String>): PidTidTokens {
    val tokens = pattern.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val raw = LinkedHashSet<String>()
    val namedPids = LinkedHashSet<Int>()
    for (token in tokens) {
        if (token.toIntOrNull() != null) {
            raw += token
        } else if (processNames.isNotEmpty()) {
            for ((pid, name) in processNames) if (name == token) namedPids += pid
        }
    }
    return PidTidTokens(raw, namedPids)
}

internal fun matchesPidTidTokens(entry: LogEntry, tokens: PidTidTokens): Boolean =
    tokens.rawTokens.any { it == entry.pid.toString() || it == entry.tid.toString() } || entry.pid in tokens.namedPids

private fun matchesPidTidFilter(entry: LogEntry, pidTidFilter: String, processNames: Map<Int, String>): Boolean =
    matchesPidTidTokens(entry, resolvePidTidTokens(pidTidFilter, processNames))

private fun passesTagOrKeywordFilter(entry: LogEntry, filter: Filter, regexContext: RegexEvaluationContext): Boolean =
    when (filter.mode) {
        FilterMode.TAGS -> {
            if (filter.activeTags.isEmpty() && filter.pkgPrefixes.isEmpty()) {
                true
            } else {
                val selectedExactTagPass = entry.tag in filter.activeTags
                if (selectedExactTagPass) {
                    true
                } else {
                    filter.pkgPrefixes
                        .filter { pfx -> tagMatchesPrefix(entry.tag, pfx) }
                        .any { pfx ->
                            val scopedActiveTags = filter.activeTags.filter { tag -> tagMatchesPrefix(tag, pfx) }
                            scopedActiveTags.isEmpty()
                        }
                }
            }
        }

        FilterMode.KEYWORD -> {
            if (filter.kwText.isBlank()) {
                true
            } else if (filter.kwRegex) {
                containsPattern(visibleLogLineText(entry), filter.kwText, regex = true, regexContext = regexContext)
            } else {
                tagMsgContainsPattern(entry.tag, entry.msg, filter.kwText, filter.kwRegex, regexContext = regexContext)
            }
        }
    }

// internal (not private): AppState's tag-prefix/specific-class conflict detector (Wave 2.2) needs
// the exact same "does this tag fall under this prefix" rule passesTagOrKeywordFilter uses above,
// so a prefix and a tag are never judged to conflict (or not) by two independently-maintained
// definitions of "under".
internal fun tagMatchesPrefix(tag: String, prefix: String): Boolean =
    tag == prefix || tag.startsWith("$prefix.")

private fun matchesRule(
    entry: LogEntry,
    rule: MessageRule,
    processNames: Map<Int, String>,
    regexContext: RegexEvaluationContext,
): Boolean = when (rule.target) {
    RuleTarget.PID_TID -> matchesPidTidFilter(entry, rule.pattern, processNames)
    RuleTarget.MESSAGE -> rulePatternMatches(entry, rule, regexContext)
}

private fun ruleScopeMatches(entry: LogEntry, rule: MessageRule): Boolean {
    val exact = rule.tag?.takeIf { it.isNotBlank() }
    val prefix = rule.packagePrefix?.takeIf { it.isNotBlank() }
    if (exact != null && entry.tag != exact) return false
    if (prefix != null && entry.tag != prefix && !entry.tag.startsWith("$prefix.")) return false
    return true
}

private fun rulePatternMatches(entry: LogEntry, rule: MessageRule, regexContext: RegexEvaluationContext): Boolean =
    containsPattern(entry.msg, rule.pattern, rule.regex, regexContext = regexContext)

// Single source of truth for "what counts as currently visible" — used by both computeItems()
// (applyFilter = true, the normal rendering path) and log export, so a filtered export always
// matches exactly what computeItems() would show before any collapse/expand folding.
fun visibleEntries(tab: LogTab, applyFilter: Boolean = true): List<LogEntry> =
    visibleEntries(tab, applyFilter, RegexEvaluationContext())

internal fun visibleEntries(
    tab: LogTab,
    applyFilter: Boolean,
    regexContext: RegexEvaluationContext,
): List<LogEntry> =
    if (applyFilter) {
        tab.logData.filter { passesFilter(it, tab.filter, tab.analysis.processNames, regexContext) }
    } else {
        tab.logData
    }

// Ids are strictly increasing within a tab (parser, merge, and tailing all guarantee it) and
// dense enough that a BitSet id-set is ~1 bit/entry — the boxed HashSet<Int> equivalents these
// replace cost ~50 bytes/entry and dominated computeItems' GC churn on multi-million-line files.
private fun idBitSet(entries: List<LogEntry>): java.util.BitSet {
    val bits = java.util.BitSet((entries.lastOrNull()?.id ?: 0) + 1)
    entries.forEach { bits.set(it.id) }
    return bits
}

// Memo of the filter/sequence work from a tab's last computeItems call. An expand/collapse
// click changes only tab.expanded — but used to re-run the full-file filter pass (~4s at 10M
// lines with a keyword filter) and the sequence scan (seconds more with sequence defs enabled)
// just to splice different children into the item list. Everything here is invariant under
// `expanded`, so those clicks now reuse it and only rebuild the item list itself.
// Keyed per (tab, applyFilter) since the split "Original" panel computes applyFilter=false
// alongside the main panel's true. Invalidated by identity checks (logData/analysis are
// replaced wholesale on reload/tailing) plus Filter equality, and dropped on tab close.
// Each field is an independent piece of the last computeItems() result — bundling any subset
// into a nested type would just move the same values one level deeper without reducing them.
@Suppress("LongParameterList")
private class TabComputeCache(
    val logData: List<LogEntry>,
    val stackGroupsRef: List<StackTraceGroup>,
    val filter: Filter,
    val visible: List<LogEntry>,
    val seqGroups: List<SeqGroup>?,
    val filteredStackGroups: List<StackTraceGroup>?,
    // Derived from seqGroups only, but linear in total swallowed lines — a sequence def without
    // an end pattern can swallow most of a 10M-line file, making these worth memoizing too.
    val seqOwnerBySwallowed: Map<Int, String>?,
    val seqChildBits: java.util.BitSet?,
    // Task 4: crossing top-level sequence pairs on DIFFERENT threads, straight out of the
    // crossing-resolution pass just below (never a second scan) — see cachedCrossingThreadHintsFor's
    // own doc for why this rides along in the cache instead of being recomputed by FilterPanel.
    val crossingThreadHints: List<CrossingThreadHint>,
    // The full result of the last compute, kept so a single stack-group toggle can splice member
    // rows in/out instead of re-materializing millions of LogItems (see spliceStackToggle).
    val items: List<LogItem>?,
    val expanded: Set<String>,
    val manualBlocks: List<ManualCollapseBlock>,
)

// Task 4: one crossing top-level-sequence pair (utils/Filter.kt's seqHostsSeqDirect resolution)
// whose start entries sit on DIFFERENT threads — precisely the "two parallel runs interleaved"
// case Wave 2.1 thread-scoping exists to separate; a pair on the SAME tid is left out entirely
// (scoping both to that one shared tid wouldn't separate them — see the filter at its one call
// site below). Carries each side's own defId + that SPECIFIC occurrence's own tid rather than a
// gid: FilterPanel's "scope both" action mutates the underlying SequenceDefs directly, and a def
// with multiple occurrences in the log needs to know WHICH occurrence's tid crossed here — the
// def's OWN first match overall (what Task 3's "turn scoping on" resolves) is not necessarily the
// same run that's crossing in THIS pair.
data class CrossingThreadHint(val hostDefId: String, val hostTid: Int, val guestDefId: String, val guestTid: Int)

private val computeCacheByTab = java.util.concurrent.ConcurrentHashMap<String, TabComputeCache>()

fun invalidateComputeCache(tabId: String) {
    computeCacheByTab.remove("$tabId#true")
    computeCacheByTab.remove("$tabId#false")
}

// Read-only peek at the memoized sequence groups from this tab's last computeItems(tab,
// applyFilter) call — for expansionAndIndexForEntry's largeFileMode branch (ui/LogViewer.kt),
// which needs sequence-containment membership without paying for a fresh computeItems just to get
// it. Reuses the EXACT SAME validity predicate computeItems applies to its own `prior` lookup
// above, so this can never hand back groups computed against a stale logData/analysis/filter.
// A null return means only "no cheap answer available right now" (cache empty, invalidated, built
// under a different tab/filter/analysis, or sequences disabled/not-yet-computed) — it is NEVER
// evidence that no group contains a given id; callers must treat null as "fall through," not as a
// negative membership answer. Pure read: never computes, never populates the cache, never mutates
// anything, so calling it has no effect on later computeItems calls.
fun cachedSeqGroupsFor(tab: LogTab, applyFilter: Boolean): List<SeqGroup>? =
    computeCacheByTab["${tab.id}#$applyFilter"]?.takeIf {
        it.logData === tab.logData &&
            it.stackGroupsRef === tab.analysis.stackTraceGroups &&
            it.filter == tab.filter
    }?.seqGroups

// Read-only peek at the filtered entry list from this tab's last computeItems(tab, applyFilter)
// call. Same validity predicate, same null contract as cachedSeqGroupsFor above: null means "no
// cheap answer available right now" (cache empty, invalidated, or built under a different
// tab/filter/analysis) — NEVER "this tab has no visible entries." Pure read, same as
// cachedSeqGroupsFor: never computes, never populates the cache, never mutates anything.
fun cachedVisibleEntriesFor(tab: LogTab, applyFilter: Boolean): List<LogEntry>? =
    computeCacheByTab["${tab.id}#$applyFilter"]?.takeIf {
        it.logData === tab.logData &&
            it.stackGroupsRef === tab.analysis.stackTraceGroups &&
            it.filter == tab.filter
    }?.visible

// Task 4: read-only peek at the crossing-different-thread sequence pairs found by this tab's last
// computeItems(tab, applyFilter) call — see CrossingThreadHint's own doc for the field shape and
// TabComputeCache's doc for why this rides along in the SAME cache entry instead of FilterPanel
// re-deriving it (which would mean re-running SeqComputer's whole scan just to answer "should I
// show a hint?"). Same contract as cachedSeqGroupsFor/cachedVisibleEntriesFor: null means "no
// cheap answer available right now" (cache cold/stale) — a caller must treat that as "don't show a
// hint yet," never as "no crossings exist." Pure read, same three guarantees as its siblings.
fun cachedCrossingThreadHintsFor(tab: LogTab, applyFilter: Boolean): List<CrossingThreadHint>? =
    computeCacheByTab["${tab.id}#$applyFilter"]?.takeIf {
        it.logData === tab.logData &&
            it.stackGroupsRef === tab.analysis.stackTraceGroups &&
            it.filter == tab.filter
    }?.crossingThreadHints

// Fast path for the single most common expand/collapse: toggling one stack-trace ("crash")
// block. Its rendered footprint is strictly local — the header flips its `expanded` flag and the
// member rows appear/disappear immediately after it; nothing else in the item list changes
// (top-level filtering by owner-sequence expansion, skipIds, and nested placement all depend on
// sequence/manual gids, never on a stack gid). So instead of re-materializing millions of
// LogItems (~0.5s at 10M rows, the remaining expand latency after memoization), copy the cached
// list and splice. Any condition this can't prove — different toggle kind, multi-gid change,
// header not currently visible, unexpected neighborhood — returns null and the caller does the
// full rebuild, so the fallback is exactly the previous behavior.
@Suppress("ReturnCount")
private fun spliceStackToggle(tab: LogTab, prior: TabComputeCache): List<LogItem>? {
    val priorItems = prior.items ?: return null
    if (prior.manualBlocks != tab.manualBlocks) return null
    val added = tab.expanded - prior.expanded
    val removed = prior.expanded - tab.expanded
    if (added.size + removed.size != 1) return null
    val gid = added.firstOrNull() ?: removed.first()
    val expanding = added.isNotEmpty()
    val groups = prior.filteredStackGroups ?: tab.analysis.stackTraceGroups
    val group = groups.firstOrNull { it.gid == gid } ?: return null
    val idx = priorItems.indexOfFirst { it is LogItem.StackTraceHeader && it.gid == gid }
    if (idx < 0) return null
    val header = priorItems[idx] as LogItem.StackTraceHeader
    if (header.expanded == expanding) return null

    val result = ArrayList<LogItem>(priorItems.size + if (expanding) group.memberIds.size else 0)
    result.addAll(priorItems.subList(0, idx))
    result.add(header.copy(expanded = expanding))
    if (expanding) {
        group.memberIds.forEach { id ->
            tab.rmap[id]?.let { result.add(LogItem.Row(it, header.indent + 1, DANGER_RED)) }
        }
        result.addAll(priorItems.subList(idx + 1, priorItems.size))
    } else {
        val end = idx + 1 + group.memberIds.size
        if (end > priorItems.size) return null
        for (k in idx + 1 until end) if (priorItems[k] !is LogItem.Row) return null
        result.addAll(priorItems.subList(end, priorItems.size))
    }
    return result
}

// A child container to render within some range of `data`: either a top-level auto-detected
// sequence, a nested sub-sequence within one, or a user-created manual collapse range. All three
// resolve to an index range into `data`, which is what makes it possible to nest a manual block
// inside a sequence (or vice versa) without ever re-running sequence detection on a sub-list —
// see the long comment above the hosting-resolution block in computeItems for why that matters.
private sealed class ChildRef {
    abstract val start: Int

    // Upper bound used both to advance past this child in the parent's pointer walk and, when
    // this child is expanded, as the jump target after rendering it in full. For a manual range
    // that hosts a "crossing" sequence extending past its own declared end, this is that
    // sequence's endExclusive, not the manual block's own range — see ManualC.declaredEnd for the
    // manual block's own (unextended) bound, used when it's collapsed rather than expanded.
    abstract val end: Int

    data class SeqC(val sg: SeqGroup, override val start: Int) : ChildRef() {
        override val end get() = sg.endExclusive
    }

    data class NestedC(val ng: NestedSeqGroup, override val start: Int) : ChildRef() {
        override val end get() = ng.endExclusive
    }

    data class ManualC(val mr: ManualRange, val declaredEnd: Int, override val end: Int) : ChildRef() {
        override val start get() = mr.range.first
    }
}

private data class ManualRange(val block: ManualCollapseBlock, val range: IntRange)

// Resolves each of `blocks` (must already be filtered to enabled ones) to an index range into
// whatever entry list [indexOfId] answers against — `data` from computeItems, or a tab's plain
// visibleEntries() from expandSelectionThroughCollapsedBlocks below — which is exactly why this
// takes the lookup as a parameter rather than closing over computeItems' own `data`/`indexOfId`.
// A block whose anchor (or, for RANGE, whose endId) isn't found by [indexOfId] — e.g. filtered out
// of the list being resolved against — is silently dropped via mapNotNull rather than surfaced as
// an error, matching the pre-hoist inline behavior. Byte-identical mapNotNull shape and sort order
// to that inline version — do not touch either without re-checking selectTopLevelManualRanges
// below, which depends on this exact order.
private fun manualRangesFor(
    blocks: List<ManualCollapseBlock>,
    dataLastIndex: Int,
    indexOfId: (Int) -> Int?,
): List<ManualRange> = blocks.mapNotNull { block ->
    val anchor = indexOfId(block.anchorId) ?: return@mapNotNull null
    val range = when (block.direction) {
        ManualCollapseDirection.TO_START -> 0..anchor
        ManualCollapseDirection.TO_END -> anchor..dataLastIndex
        ManualCollapseDirection.RANGE -> {
            val end = block.endId?.let(indexOfId) ?: return@mapNotNull null
            minOf(anchor, end)..maxOf(anchor, end)
        }
    }
    ManualRange(block, range)
}.sortedWith(compareBy<ManualRange> { it.range.first }.thenByDescending { it.range.last })

// Reproduces, exactly, the selection rule the old per-index walk used: scanning left to right,
// the first (widest, per the sort below) range starting at each index wins; any other range whose
// start falls inside an already-selected range is silently dropped. `ranges` must already be
// sorted by `range.first` ascending, `range.last` descending, so the first entry recorded per
// start index in `byStart` is the widest one — matching the old `firstOrNull` tie-break. This is a
// pre-existing, out-of-scope limitation (overlapping manual blocks aren't reconciled) — preserved
// unchanged, just computed in O(n + m) instead of the old O(n * m).
private fun selectTopLevelManualRanges(dataSize: Int, ranges: List<ManualRange>): List<ManualRange> {
    val byStart = HashMap<Int, ManualRange>()
    for (r in ranges) byStart.putIfAbsent(r.range.first, r)
    val result = mutableListOf<ManualRange>()
    var i = 0
    while (i < dataSize) {
        val r = byStart[i]
        if (r == null) {
            i += 1
        } else { result += r; i = r.range.last + 1 }
    }
    return result
}

// W4: a single collapsed TO_START/TO_END manual block on a 10M-line tab resolves to `0..anchor`
// (or `anchor..lastIndex`) — the entire file. Enumerating that many ids used to cost a 40MB
// IntArray, a ~200MB boxed ArrayList (`IntRange.map`), and a ~400MB LinkedHashSet, all on the
// composition thread, before any diagram code ran. 200_000 is comfortably above any selection a
// person actually makes by hand (a boxed HashSet<Int> at that size is ~12MB, sub-50ms) while
// staying far below the multi-million-id case that froze or OOM'd the app.
const val SELECTION_EXPANSION_MAX_IDS = 200_000

/**
 * Result of [expandSelectionThroughCollapsedBlocks]. [ids] is the expanded selection, identical to
 * the input [Set] instance when nothing needed expanding. [boundExceeded] is true when a matched,
 * currently-collapsed manual block's hidden range was too large to enumerate within
 * [SELECTION_EXPANSION_MAX_IDS] — [ids] then carries only that block's two boundary ids (still
 * enough to fix the overall min/max span) rather than its full interior. The sole caller,
 * [com.indagium.ui.Seq3Session.rangeFor], must treat that case as the plain inclusive span (the
 * same thing an empty [com.indagium.diagram3.Seq3Range.Ids.selectedIds] already means) rather than
 * read [ids] as the curated, exact selection — passing just the two boundary ids as `selectedIds`
 * would silently narrow the diagram to those two lines instead of everything between them.
 */
data class SelectionExpansion(val ids: Set<Int>, val boundExceeded: Boolean = false)

/**
 * Expands a row selection so selecting a collapsed header means what it looks like it means:
 * every line that fold hides — identical to what the user would have selected after uncollapsing
 * the block by hand.
 *
 * Only expands a fold that is currently COLLAPSED (`gid !in tab.expanded`); an already-open fold's
 * interior rows are individually selectable, so a header id already in [selected] there says
 * exactly what the user meant, with nothing to add. `TO_START` is the case that makes this
 * function necessary at all: Filter.kt defines it as `0..anchor`, so everything it hides has
 * strictly LOWER ids than the only selectable row — a plain `min..max` id span can never reach it.
 *
 * Ordered so the cheap, common cases never pay for the expensive one: stack-trace and sequence
 * membership are answered straight off ids the group models already carry (no index arithmetic
 * needed), so only a matched, currently-collapsed manual block pays for resolving the visible-entry
 * index space and calling [manualRangesFor]. Returns the identical [selected] instance (wrapped,
 * `boundExceeded = false`) when nothing expands, so a plain-row selection allocates nothing beyond
 * the wrapper on this path.
 */
fun expandSelectionThroughCollapsedBlocks(tab: LogTab, selected: Set<Int>, applyFilter: Boolean = true): SelectionExpansion {
    if (selected.isEmpty()) return SelectionExpansion(selected)

    // Resolved at most once, and only if a fold actually needs it. Both the manual-block and the
    // sequence-group phase below want the visible list, so asking each of them independently
    // would run a second full filter pass over the whole log whenever the cache is cold and both
    // phases match.
    var visibleMemo: List<LogEntry>? = null

    fun visible(): List<LogEntry> =
        visibleMemo ?: (cachedVisibleEntriesFor(tab, applyFilter) ?: visibleEntries(tab, applyFilter)).also { visibleMemo = it }

    // Three independent phases, in the same order as before extraction (stack traces, then manual
    // blocks, then sequence groups) — each just contributes ids to add, so splitting them out
    // keeps this function's own complexity low without changing what gets expanded or when
    // visible() first gets called.
    val manual = manualBlockExpansionIds(tab, selected, ::visible)
    val toAdd = stackTraceExpansionIds(tab, selected) + manual.ids + seqGroupExpansionIds(tab, selected, applyFilter, ::visible)

    val ids = if (toAdd.isEmpty()) selected else LinkedHashSet(selected).apply { addAll(toAdd) }
    return SelectionExpansion(ids, manual.boundExceeded)
}

// Stack traces: same access pattern as expansionAndIndexForEntry's largeFileMode branch
// (ui/LogViewer.kt) — analysis.stackTraceGroups already lists each group's member ids outright.
private fun stackTraceExpansionIds(tab: LogTab, selected: Set<Int>): List<Int> {
    val ids = mutableListOf<Int>()
    for (g in tab.analysis.stackTraceGroups) {
        if (g.rid in selected && g.gid !in tab.expanded) ids += g.memberIds
    }
    return ids
}

// Manual collapse blocks. Gather the collapsed candidates first — cheap, no visible-entry
// resolution needed — and only pay for indexOfId/manualRangesFor if any actually matched. In
// practice the cache below is warm: the user just clicked a collapsed header, which is what ran
// computeItems(tab, applyFilter) in the first place.
// W4: bundles the manual-block phase's contribution with whether it had to stop short of full
// enumeration — see SelectionExpansion's doc for why the caller needs both.
private class ManualExpansionIds(val ids: List<Int>, val boundExceeded: Boolean)

private fun manualBlockExpansionIds(tab: LogTab, selected: Set<Int>, visible: () -> List<LogEntry>): ManualExpansionIds {
    val collapsedManual = tab.manualBlocks.filter { it.enabled && it.anchorId in selected && it.id !in tab.expanded }
    if (collapsedManual.isEmpty()) return ManualExpansionIds(emptyList(), false)
    val visibleEntries = visible()
    val ids = IntArray(visibleEntries.size) { visibleEntries[it].id }

    fun indexOfId(id: Int): Int? = java.util.Arrays.binarySearch(ids, id).takeIf { it >= 0 }
    // `ids` is already the id-per-index array built above for indexOfId's binary search, so
    // slicing straight out of it below costs no extra boxing beyond what landing the ints in
    // `result` (a List<Int>) always needs — no intermediate `IntRange.map` ArrayList<Integer>.
    val result = mutableListOf<Int>()
    var budget = SELECTION_EXPANSION_MAX_IDS
    var boundExceeded = false
    manualRangesFor(collapsedManual, visibleEntries.lastIndex, ::indexOfId).forEach { mr ->
        val size = mr.range.last - mr.range.first + 1
        if (size > budget) {
            // Over budget: don't enumerate, but still contribute this range's own endpoints so
            // the caller's min/max span calculation stays correct without materializing what's
            // between them. See SelectionExpansion's doc — the caller must treat this as "fell
            // back to the plain span," not as "the exact selection is these two ids."
            boundExceeded = true
            result += ids[mr.range.first]
            result += ids[mr.range.last]
        } else {
            for (i in mr.range) result += ids[i]
            budget -= size
        }
    }
    return ManualExpansionIds(result, boundExceeded)
}

// Sequence groups, including nested children — only when sequence folding is on at all,
// mirroring computeItems' own gate, so a tab with sequences disabled never pays for
// computeSeqGroups just to answer this. cachedSeqGroupsFor null means "no cheap answer right
// now," never "no groups exist" — fall back to computing fresh.
private fun seqGroupExpansionIds(tab: LogTab, selected: Set<Int>, applyFilter: Boolean, visible: () -> List<LogEntry>): List<Int> {
    if (!tab.filter.seqOn || tab.filter.sequences.none { it.enabled }) return emptyList()
    val groups = cachedSeqGroupsFor(tab, applyFilter) ?: computeSeqGroups(visible(), tab.filter.sequences)
    val result = mutableListOf<Int>()
    for (sg in groups) {
        if (sg.rid in selected && sg.gid !in tab.expanded) {
            // A collapsed OUTER header hides its whole subtree unconditionally — including each
            // nested group's own header row, not just its ch — matching computeItems' own
            // totalCh accounting (plain.size + nested.sumOf { 1 [the ng.rid row] + ch.size }).
            // Skipping ng.rid here would leave its tag unseeded even though the row is bound to
            // land inside the resulting id range anyway (resolveIdsRange is a continuous
            // min..max scan) — precisely the hiddenEntries regression this function exists to fix.
            result += sg.plain
            sg.nested.forEach { ng -> result += listOf(ng.rid) + ng.ch }
        }
        for (ng in sg.nested) {
            // The nested header ITSELF collapsed while its parent is already open: only its own
            // ch is hidden — nested groups are a single level deep, there's nothing further to
            // recurse into.
            if (ng.rid in selected && ng.gid !in tab.expanded) result += ng.ch
        }
    }
    return result
}

// Cooperative-cancellation hook for computeItems/computeSeqGroups (P-01). Both are plain,
// non-suspend functions called from several contexts with no CoroutineScope at all — the
// synchronous small-file render path, ControlServer.kt's get_visible_lines route, and every
// existing test — so cancellation can't just be a suspend-function/ensureActive() call baked in
// directly. Instead the caller that actually runs on a cancellable coroutine (LogViewer.kt's
// large-file async path) supplies a check; everyone else uses the no-op default, so this changes
// nothing for any call site that doesn't opt in. Invoked periodically (not every loop iteration)
// from the hot loops below so the no-op case stays negligible overhead.
fun interface CancellationCheck {
    operator fun invoke()
}

private val NoCancellationCheck = CancellationCheck {}

// How often (in loop iterations) the hot loops below — and SeqComputer.kt's own scan — poll the
// cancellation check — frequent enough that a cancelled computation stops promptly, infrequent
// enough that the check call itself (a no-op in the common case, ensureActive() in the
// cancellable case) never shows up as measurable overhead. internal, not private: SeqComputer.kt
// is a different file in the same package, and Kotlin's top-level `private` is file-scoped, not
// package-scoped.
internal const val CANCELLATION_CHECK_INTERVAL = 4096

// Complexity is inherent: sequence detection, manual-collapse interleaving, and recursive
// container rendering are all coupled — splitting them would require passing shared mutable state.
fun computeItems(tab: LogTab, applyFilter: Boolean, cancellationCheck: CancellationCheck = NoCancellationCheck): List<LogItem> =
    computeItems(tab, applyFilter, cancellationCheck, RegexEvaluationContext())

// storeInCache defaults true for every existing caller of this overload except the probing ones
// below (LogViewer.kt's expansionAndIndexForEntry, AppState.kt's visibleExpandableGroupIds /
// scheduleSearchRecompute), which pass a HYPOTHETICAL tab.copy(expanded = ...) — not the tab's
// actually-rendered fold state — purely to test "does entryId become visible if I open this
// group?" or to search over a fully-expanded copy. The cache is keyed "$tabId#$applyFilter" only
// (deliberately NOT including `expanded` — see the cacheKey doc below), so writing a probe's
// result there used to clobber whatever the real render path had cached for tab.expanded, making
// its `prior.expanded` disagree with the next real toggle's tab.expanded by more than the one gid
// spliceStackToggle requires, silently falling back to a full recompute on almost every toggle
// after a probe ran. Probe callers pass storeInCache = false instead: they still READ a warm cache
// (harmless, and lets them reuse the splice fast path too) but never WRITE their speculative result
// into it.
internal fun computeItems(
    tab: LogTab,
    applyFilter: Boolean,
    regexContext: RegexEvaluationContext,
    storeInCache: Boolean = true,
): List<LogItem> = computeItems(tab, applyFilter, NoCancellationCheck, regexContext, storeInCache)

// LoopWithTooManyJumpStatements: the crossing-thread-hint scan's inner loop uses continue/break
// as independent early-outs (already-claimed guest, sorted-start cutoff, contained pair) — see
// the comments at each jump site just below for what each one skips and why.
@Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
internal fun computeItems(
    tab: LogTab,
    applyFilter: Boolean,
    cancellationCheck: CancellationCheck,
    regexContext: RegexEvaluationContext,
    storeInCache: Boolean = true,
): List<LogItem> {
    val sequences = tab.filter.sequences
    // Deliberately NOT keyed on tab.expanded — see spliceStackToggle just above, whose entire
    // point is finding THIS SAME cache entry across an expanded-set change so a single group
    // toggle can splice member rows in/out instead of re-running the whole computation. That
    // sharing is also exactly why storeInCache exists: a probing caller must not overwrite this
    // entry with a result built from a hypothetical expanded set (see the doc above).
    val cacheKey = "${tab.id}#$applyFilter"
    val prior = computeCacheByTab[cacheKey]?.takeIf {
        it.logData === tab.logData &&
            it.stackGroupsRef === tab.analysis.stackTraceGroups &&
            it.filter == tab.filter
    }
    val data = prior?.visible ?: visibleEntries(tab, applyFilter, regexContext)
    var fullSeqGroups: List<SeqGroup>? = prior?.seqGroups
    var fullFilteredStackGroups: List<StackTraceGroup>? = prior?.filteredStackGroups
    var fullSeqOwner: Map<Int, String>? = prior?.seqOwnerBySwallowed
    var fullSeqChildBits: java.util.BitSet? = prior?.seqChildBits
    // Task 4: NOT memoized the way fullSeqGroups etc. are — recomputed fresh below whenever the
    // main (non-splice, non-early-return) path runs, since it's cheap (bounded by sequence-group
    // COUNT, not file size — see the crossing-resolution block's own doc). Defaults to prior's
    // value purely so the splice-toggle fast path (just below) carries it forward unchanged,
    // matching how that path leaves every OTHER seq-derived field untouched too.
    var crossingThreadHints: List<CrossingThreadHint> = prior?.crossingThreadHints ?: emptyList()

    fun storeCache(items: List<LogItem>) {
        if (!storeInCache) return
        if (regexContext.hasTimedOut) {
            computeCacheByTab.remove(cacheKey)
            return
        }
        computeCacheByTab[cacheKey] = TabComputeCache(
            logData = tab.logData,
            stackGroupsRef = tab.analysis.stackTraceGroups,
            filter = tab.filter,
            visible = data,
            seqGroups = fullSeqGroups,
            filteredStackGroups = fullFilteredStackGroups,
            seqOwnerBySwallowed = fullSeqOwner,
            seqChildBits = fullSeqChildBits,
            crossingThreadHints = crossingThreadHints,
            items = items,
            expanded = tab.expanded,
            manualBlocks = tab.manualBlocks,
        )
    }

    if (prior != null) {
        spliceStackToggle(tab, prior)?.let { spliced ->
            storeCache(spliced)
            return spliced
        }
    }

    // Sequence groups are always computed exactly once, against the full filtered `data` — never
    // against a manual-collapse sub-range. A manual block's boundary must never truncate or split
    // an auto-detected sequence that spans across it; manual-block interleaving is handled purely
    // as a rendering/nesting concern below, layered on top of this single ground-truth pass.
    val seqGroups: List<SeqGroup> = if (tab.filter.seqOn && sequences.any { it.enabled }) {
        fullSeqGroups ?: computeSeqGroups(data, sequences, cancellationCheck, regexContext).also { fullSeqGroups = it }
    } else {
        emptyList()
    }

    // Stack-trace folding is always-on, independent of user-defined sequences and of manual
    // blocks. Also always computed against the full `data` now, for the same reason as seqGroups
    // above — this incidentally fixes the same class of truncation bug for a stack trace that
    // straddles a manual-block boundary.
    val allStackGroups: List<StackTraceGroup> = run {
        val cached = tab.analysis.stackTraceGroups
        when {
            // Analysis still computing in the background after a load: render unfolded rather
            // than blocking this compute on a full multi-second stack-trace scan. When the
            // analysis lands, tab.analysis is replaced and the item list recomputes with folding.
            tab.analysis.pending -> emptyList()
            // Analysis is complete — cached is trusted as ground truth even when empty. P-02: a
            // completed analysis that genuinely found no stack traces used to be indistinguishable
            // from "never analyzed," so this branch used to recompute from `data` unconditionally
            // every time cached happened to be empty, on every composition.
            data.size == tab.logData.size -> cached
            else -> fullFilteredStackGroups ?: run {
                val dataIdBits = idBitSet(data)
                cached.mapNotNull { group ->
                    if (!dataIdBits.get(group.rid)) {
                        null
                    } else {
                        val visibleMembers = group.memberIds.filter { dataIdBits.get(it) }
                        group.copy(memberIds = visibleMembers).takeIf { visibleMembers.isNotEmpty() }
                    }
                }.also { fullFilteredStackGroups = it }
            }
        }
    }

    val manualBlocksEnabled = tab.manualBlocks.filter { it.enabled }
    if (seqGroups.isEmpty() && allStackGroups.isEmpty() && manualBlocksEnabled.isEmpty()) {
        return data.map { LogItem.Row(it, 0) }.also { storeCache(it) }
    }

    val defMap = sequences.associateBy { it.id }

    // A sequence with no explicit end pattern can swallow everything up to the next start match
    // (or end-of-log) as unstructured "plain" children — including an exception/ANR block that has
    // nothing to do with the sequence. Render it nested one level inside the sequence's plain
    // children *only while that sequence is already expanded* (a nice "this crash happened during
    // X" grouping); otherwise render it as its own independent, always-visible collapsible block —
    // crash navigation never has to search for or blindly expand a group to find it.
    val seqOwnerGidBySwallowedId = fullSeqOwner ?: buildMap<Int, String> {
        seqGroups.forEach { sg -> sg.plain.forEach { id -> put(id, sg.gid) } }
    }.also { fullSeqOwner = it }
    val stackGroups = allStackGroups.filter { g ->
        val ownerGid = seqOwnerGidBySwallowedId[g.rid]
        ownerGid == null || ownerGid !in tab.expanded
    }
    val stackGroupByRid = stackGroups.associateBy { it.rid }
    val nestedStackGroupByRid = (allStackGroups - stackGroups.toSet()).associateBy { it.rid }

    // Kept in the memoization cache for TabComputeCache's shape/downstream tooling, though the
    // recursive renderer below no longer needs a global "is this id swallowed by some sequence"
    // bitset — coverage is resolved per recursion level instead (see renderRange), which correctly
    // distinguishes "covered by the sequence I'm currently rendering" from "covered by some other
    // sequence entirely," something a single global bitset could not.
    if (fullSeqChildBits == null) {
        fullSeqChildBits = java.util.BitSet().also { bits ->
            seqGroups.forEach { g ->
                g.plain.forEach(bits::set)
                g.nested.forEach { ng ->
                    bits.set(ng.rid)
                    ng.ch.forEach(bits::set)
                }
            }
        }
    }

    // Ids ascend within data, so id->index lookup is a binary search instead of a boxed map.
    val dataIds = IntArray(data.size) { data[it].id }

    fun indexOfId(id: Int): Int? = java.util.Arrays.binarySearch(dataIds, id).takeIf { it >= 0 }

    fun rootIdxOf(rid: Int): Int = indexOfId(rid) ?: -1

    val allManualRanges = manualRangesFor(manualBlocksEnabled, data.lastIndex, ::indexOfId)

    val topLevelManualCandidates = selectTopLevelManualRanges(data.size, allManualRanges)

    // ── Resolve top-level sequence-vs-sequence hosting (crossing sequences) ──────────────────────
    // assignParents (SeqComputer.kt) only nests a candidate that's fully CONTAINED in another
    // (child.endExclusive <= parent.endExclusive); two roots whose ranges partially overlap
    // ("cross" — neither contains the other) both surface here as independent top-level SeqGroups.
    // Left alone they'd both land in topChildren with overlapping [start, end) ranges, which is
    // exactly what used to make renderRange silently drop every row from the swallowed one's start
    // to its own end.
    //
    // A CHAIN (A crosses B crosses C, three parallel threads each recording a sequence — the
    // originally reported scenario), not a flat fan-out: each root hosts at most its own single
    // NEXT unclaimed crossing partner (`break` after attaching one), and a root already claimed as
    // a guest is still walked as a potential HOST on its own turn (no "already hosted, skip" guard
    // on `a`) — that's what lets B both be nested under A *and* itself host C. This is provably
    // exhaustive for top-level roots: if two roots B and D both cross the same A, both ranges must
    // contain the point just before A's end, so B and D necessarily cross each other too (neither
    // can contain the other — both are roots) — top-level crossings among non-containing roots are
    // therefore always resolvable as one linear, start-ordered chain, never a "fork." An earlier
    // flat version (all hosted directly under the first starter) tried to make this cheaper by
    // folding the whole chain under one host, but that stranded the tail of a chain three or more
    // deep: a collapsed middle host's swallow walk only has as much room as ITS OWN parent's
    // recursion `hi`, and a flat fan-out gives no single level enough room to reach a
    // grandchild's territory. Chaining sidesteps that entirely — see seqEffectiveEnd (recursive,
    // just below) and the `hi` passed to a SeqC's own recursion in renderRange (now
    // seqEffectiveEnd, not endExclusive) for the other half of the fix.
    //
    // Does not touch either side's own reported header count (see the "never changes either side's
    // own count" note on the sequence-vs-manual resolution just below) — a collapsed host still
    // only *hides* its own declared children (which may, incidentally, include a hosted guest's own
    // header — see the doc on renderRange's SeqC branch); a hosted guest's tail beyond ALL its
    // ancestors' declared ends has no header left to hide under, so it falls through and renders as
    // plain rows instead (see the renderRange fallback fix).
    val seqHostsSeqDirect = HashMap<String, MutableList<SeqGroup>>()
    val seqHostedBySeqGid = HashSet<String>()
    run {
        val roots = seqGroups.sortedBy { rootIdxOf(it.rid) }
        // Task 4: collected in the SAME pass that resolves crossing hosting above — no second
        // walk over the sequence groups, let alone a second scan of the log. `a`/`b` are exactly
        // the two SeqGroups just found to cross; their own root entries (a.rid/b.rid) are each
        // that occurrence's OWN start line, so tab.rmap gives the exact tid THIS crossing pair
        // sits on — not just "some" match of either def elsewhere in the file (that's Task 3's
        // resolveSequenceStartTid, a different question). A pair whose starts share one tid is
        // filtered out here, not left for FilterPanel to filter: scoping both to the same shared
        // tid wouldn't separate them (see CrossingThreadHint's own doc).
        val hints = ArrayList<CrossingThreadHint>()
        for (i in roots.indices) {
            val a = roots[i]
            for (j in i + 1 until roots.size) {
                val b = roots[j]
                val b0 = rootIdxOf(b.rid)
                // Sorted by start: once a root starts at/after `a`'s own declared end, nothing
                // further can cross `a` either (its own end, not the chain's overall effective
                // end — each host only ever looks for a partner crossing ITS OWN span; a partner
                // crossing further down the chain is `b`'s problem to find on `b`'s own turn).
                // Evaluated BEFORE the claimed-guest check below: roots are sorted by start, so
                // this cutoff holds regardless of whether `b` happens to be already claimed — a
                // claimed root past the cutoff still can't cross `a`, and a run of claimed roots
                // right after `a` must not be walked past this point to the end of `roots` (W5;
                // was previously ordered after the claimed-guest `continue`, making the loop
                // O(roots) per host instead of O(1) amortized in the common case of a long run of
                // already-claimed roots).
                if (b0 >= a.endExclusive) break
                if (b.gid in seqHostedBySeqGid) continue // already claimed earlier in the chain
                if (b.endExclusive <= a.endExclusive) continue // contained (shouldn't happen among roots; defensive)
                seqHostsSeqDirect.getOrPut(a.gid) { mutableListOf() } += b
                seqHostedBySeqGid += b.gid
                val hostTid = tab.rmap[a.rid]?.tid
                val guestTid = tab.rmap[b.rid]?.tid
                if (hostTid != null && guestTid != null && hostTid != guestTid) {
                    hints += CrossingThreadHint(a.defId, hostTid, b.defId, guestTid)
                }
                break // exactly one direct guest per host — see the class doc above for why that's exhaustive
            }
        }
        crossingThreadHints = hints
    }

    // ── Resolve sequence-vs-manual-block hosting ──────────────────────────────────────────────
    // Top-level SeqGroups never contain one another (SeqComputer only exposes roots at this
    // level), and topLevelManualCandidates never overlap each other (by construction above) — so
    // the only containment/crossing relationships left to resolve are sequence-vs-manual pairs, at
    // two possible depths: directly under a top-level SeqGroup's own plain area, or one level
    // deeper under one of its NestedSeqGroups. On a straddling ("crossing") pair — neither fully
    // contains the other — whichever starts first hosts the other's full extent, nested one level
    // in even past the host's own declared end; on an exact range tie, the manual block hosts (a
    // manual block deliberately wrapping a whole sequence reads as "sequence lives inside my
    // selection," matching pre-existing behavior for that case). This never changes either side's
    // own reported header count, it only changes where its content renders.
    val seqHostsManualDirect = HashMap<String, MutableList<ManualRange>>()
    val nestedHostsManual = HashMap<String, MutableList<ManualRange>>()
    val manualHostsSeq = HashMap<String, MutableList<SeqGroup>>()
    val seqHostedByManualGid = HashSet<String>()
    val manualHostedGid = HashSet<String>()

    for (m in topLevelManualCandidates) {
        val m0 = m.range.first
        val m1 = m.range.last + 1
        for (sg in seqGroups) {
            // Already hosted by another crossing top-level sequence above — that host now owns
            // where it renders; leave it alone rather than layering a second, conflicting hosting
            // resolution on top (which would render it twice, once under each host).
            if (sg.gid in seqHostedBySeqGid) continue
            val s0 = rootIdxOf(sg.rid)
            val s1 = sg.endExclusive
            if (s1 <= m0 || m1 <= s0) continue
            when {
                m0 <= s0 && s1 <= m1 -> {
                    manualHostsSeq.getOrPut(m.block.id) { mutableListOf() } += sg
                    seqHostedByManualGid += sg.gid
                }

                s0 <= m0 && m1 <= s1 -> {
                    val ng = sg.nested.firstOrNull { n -> val n0 = rootIdxOf(n.rid); n0 <= m0 && m1 <= n.endExclusive }
                    if (ng != null) nestedHostsManual.getOrPut(ng.gid) { mutableListOf() } += m
                    else seqHostsManualDirect.getOrPut(sg.gid) { mutableListOf() } += m
                    manualHostedGid += m.block.id
                }

                m0 < s0 -> {
                    manualHostsSeq.getOrPut(m.block.id) { mutableListOf() } += sg
                    seqHostedByManualGid += sg.gid
                }

                else -> {
                    seqHostsManualDirect.getOrPut(sg.gid) { mutableListOf() } += m
                    manualHostedGid += m.block.id
                }
            }
        }
    }

    val topLevelManual = topLevelManualCandidates.filterNot { it.block.id in manualHostedGid }
    val topLevelSeqGroups = seqGroups.filterNot { it.gid in seqHostedByManualGid || it.gid in seqHostedBySeqGid }

    // Recursive: a hosted guest can itself host a further guest (the chain resolved above), so
    // this must walk all the way down the chain, not just one hop — `seqEffectiveEnd(it)` on the
    // direct guest, not `it.endExclusive`. seqHostedBySeqGid marks every guest at most once, so
    // this DAG is a forest of simple chains and always terminates.
    fun seqEffectiveEnd(sg: SeqGroup): Int =
        maxOf(
            sg.endExclusive,
            seqHostsManualDirect[sg.gid]?.maxOfOrNull { it.range.last + 1 } ?: 0,
            seqHostsSeqDirect[sg.gid]?.maxOfOrNull { seqEffectiveEnd(it) } ?: 0,
        )

    fun manualEffectiveEnd(m: ManualRange): Int =
        maxOf(m.range.last + 1, manualHostsSeq[m.block.id]?.maxOfOrNull { it.endExclusive } ?: 0)

    // Bound up to which an index still "inside" the currently-open (childPtr) child's span should
    // be silently swallowed (hidden by its collapse) rather than falling through to a plain Row.
    // For SeqC/NestedC this is just their own `.end` — never stretched by hosting a crossing
    // partner (see ChildRef.SeqC/NestedC), so no distinction is needed there. For ManualC it must
    // be the block's own DECLARED end, never the stretched `.end` used only for the outer sibling
    // walk (childPtr-advance, just below): an EXPANDED ManualC always jumps `idx` straight past its
    // full effective extent itself (see the ManualC branch), so this helper is only ever consulted
    // for one while COLLAPSED — and a collapsed header hides only its OWN declared content, never a
    // hosted guest's extra tail past it (that tail has no header of its own left to hide under, so
    // it must fall through and render — see the ManualC branch's collapsed case for why).
    fun swallowBoundFor(c: ChildRef): Int = if (c is ChildRef.ManualC) c.declaredEnd else c.end

    // A member row is skippable only once its group's header has actually been emitted into
    // `items` — never on the mere fact that it belongs to *some* stack-trace group (that global
    // question is exactly what the old `stackClaimedIds` bitset got wrong: header emission is
    // positional, pre-emptable by a container branch starting at the same index or jumped over by
    // a collapsed ManualC, so a member could be marked "claimed" and skipped even though nothing
    // ever rendered its header). Populated at emission time in the two stack-header branches below
    // (both collapsed and expanded — a collapsed header's own `count` already accounts for its
    // members, and expanding it later must be able to reveal them), then consulted by the member
    // skip branch. Shared across every recursion level, which is safe because renderRange's walk is
    // globally monotone in index and a member's index is always strictly greater than its group's
    // `rid` index (computeStackTraceGroups only ever appends members after the trigger, and the
    // prelude-promoted case sets `rid` to the line before the trigger) — so "was this group's
    // header emitted?" is always settled by the time any of its members is reached.
    val membersUnderEmittedStackHeader = java.util.BitSet()

    // ── Unified recursive renderer ────────────────────────────────────────────────────────────
    // Walks index range [lo, hi) into `data`, rendering `children` (sorted by start; crossing
    // siblings are already folded into one another by the hosting resolution above, so at any
    // given level they don't overlap) wherever their start position falls, and plain/stack-header
    // rows everywhere else. `hi` is a soft bound: if an expanded child's own true end extends past
    // it (the crossing case resolved above), that child is still rendered in full via recursion and
    // the cursor simply jumps to its true end, which is >= hi — the `while (idx < hi)` loop then
    // exits on its own next check, no special-casing needed.
    //
    // A collapsed SeqHeader/NestedSeqHeader still walks its interior position-by-position (does
    // NOT jump) so an escaped stack-trace header inside it can still surface, matching pre-existing
    // sequence behavior. A collapsed ManualHeader, by contrast, always jumps straight to its own
    // declared end — manual blocks are a deliberate, harder collapse than sequences and already
    // fully hid their interior (including any escaped stack trace within it) before this change;
    // preserved as-is rather than changed as a side effect of this fix.
    // scopeTid/foreignIndent/foreignColor describe the single thread-scoped ancestor whose
    // EXPANDED interior this particular call is directly walking (set only on the recursive call
    // made just below for that ancestor's own content — never propagated further down through a
    // nested child's own recursion, which instead computes its own scopeTid from ITS OWN def and
    // uses THIS level's indent/ambientColor as its own foreign fallback, one level at a time).
    // SeqComputer's childIds already excludes a foreign-tid entry from the group's reported plain
    // children for exactly the same reason this exists: an entry whose tid doesn't match scopeTid
    // fell inside the index span by coincidence, not because it's part of the run, so it must
    // render as a plain row at the ENCLOSING level's indent/color rather than nested and tinted.
    // Threading this down as call parameters (rather than, say, pre-filtering the range before
    // recursing) keeps the single index-walk loop below as the one place that decides where each
    // row lands, so it stays trivially compatible with the crossing-chain/manual-hosting resolution
    // already layered on top of it.
    fun renderRange(
        lo: Int,
        hi: Int,
        indent: Int,
        ambientColor: Color?,
        children: List<ChildRef>,
        scopeTid: Int? = null,
        foreignIndent: Int = indent,
        foreignColor: Color? = ambientColor,
    ): List<LogItem> {
        val items = ArrayList<LogItem>(hi - lo)
        var childPtr = 0
        var idx = lo
        var sinceCancellationCheck = 0
        while (idx < hi) {
            // The dominant hot loop in computeItems (P-01) — periodically give a cancelled caller
            // a chance to stop instead of running this whole range (potentially the whole file)
            // to completion on a superseded computation. Re-entered per recursion level, so a
            // deeply nested render still gets checked, just against each level's own local count.
            if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
                sinceCancellationCheck = 0
                cancellationCheck()
            }
            while (childPtr < children.size && children[childPtr].end <= idx) childPtr++
            val child = children.getOrNull(childPtr)?.takeIf { it.start == idx }
            val entry = data[idx]
            when {
                child is ChildRef.SeqC -> {
                    val sg = child.sg
                    val exp = sg.gid in tab.expanded
                    val totalCh = sg.plain.size + sg.nested.sumOf { ng -> 1 + ng.ch.size }
                    val color = defMap[sg.defId]?.color ?: SEQ_COLORS.first()
                    items += LogItem.SeqHeader(entry, sg.gid, indent, exp, totalCh, color, defMap[sg.defId]?.scopeTid)
                    if (exp) {
                        val kids = (
                            sg.nested.map { ng -> ChildRef.NestedC(ng, rootIdxOf(ng.rid)) } +
                                (seqHostsManualDirect[sg.gid].orEmpty()).map { m ->
                                    ChildRef.ManualC(m, m.range.last + 1, manualEffectiveEnd(m))
                                } +
                                (seqHostsSeqDirect[sg.gid].orEmpty()).map { hosted ->
                                    ChildRef.SeqC(hosted, rootIdxOf(hosted.rid))
                                }
                        ).sortedBy { it.start }
                        // hi = seqEffectiveEnd(sg), NOT sg.endExclusive: when sg is itself a link
                        // in a chain (hosts a guest which may host a further guest), a COLLAPSED
                        // guest doesn't jump — it relies on this recursion's own idx walk (the
                        // fallback swallow, bounded by swallowBoundFor) to keep going long enough
                        // to reach whatever falls through past its own declared end. Capping hi at
                        // sg's own declared end stranded that walk one level up from where a
                        // three-or-more-deep chain's tail actually lands — the collapsed guest's
                        // OWN swallow would exit into this recursion's `while (idx < hi)` check,
                        // which used to fail immediately, silently dropping everything from there
                        // to sg's true effective end with no row, no header, and no count. A fully
                        // EXPANDED sg still jumps `idx` straight past hi on its own (see just
                        // below), so widening hi here is a no-op for that case and only matters for
                        // the collapsed one.
                        items += renderRange(
                            idx + 1, seqEffectiveEnd(sg), indent + 1, color, kids,
                            scopeTid = defMap[sg.defId]?.scopeTid, foreignIndent = indent, foreignColor = ambientColor,
                        )
                        idx = seqEffectiveEnd(sg)
                    } else {
                        idx += 1
                    }
                }

                child is ChildRef.NestedC -> {
                    val ng = child.ng
                    val exp = ng.gid in tab.expanded
                    val color = defMap[ng.defId]?.color ?: ambientColor ?: SEQ_COLORS.first()
                    items += LogItem.SeqHeader(entry, ng.gid, indent, exp, ng.ch.size, color, defMap[ng.defId]?.scopeTid)
                    if (exp) {
                        val kids = nestedHostsManual[ng.gid].orEmpty()
                            .map { m -> ChildRef.ManualC(m, m.range.last + 1, m.range.last + 1) }
                            .sortedBy { it.start }
                        items += renderRange(
                            idx + 1, ng.endExclusive, indent + 1, color, kids,
                            scopeTid = defMap[ng.defId]?.scopeTid, foreignIndent = indent, foreignColor = ambientColor,
                        )
                        idx = ng.endExclusive
                    } else {
                        idx += 1
                    }
                }

                child is ChildRef.ManualC -> {
                    val mr = child.mr
                    val block = mr.block
                    val exp = block.id in tab.expanded
                    // The anchor entry (what the header displays) isn't necessarily at
                    // range.first — TO_END and some RANGE blocks anchor at the other end.
                    val headerEntry = indexOfId(block.anchorId)?.let { data[it] } ?: entry
                    items += LogItem.ManualHeader(headerEntry, block.id, block.direction, exp, mr.range.count(), block.color)
                    if (exp) {
                        val kids = manualHostsSeq[block.id].orEmpty()
                            .map { sg -> ChildRef.SeqC(sg, rootIdxOf(sg.rid)) }
                            .sortedBy { it.start }
                        // Render the manual block's full range (the anchor entry may sit at
                        // either end of it — TO_START/TO_END/RANGE all place it differently) and
                        // filter the anchor's own row out afterward, matching the header, which
                        // already displays that entry.
                        val inner = renderRange(mr.range.first, mr.range.last + 1, indent + 1, block.color, kids)
                        items += inner.filterNot { it is LogItem.Row && it.entry.id == block.anchorId }
                        // Jump past the FULL effective extent, including a hosted crossing
                        // sequence's tail beyond this block's own declared end — the soft-bound
                        // recursion just above already rendered every row in it (renderRange's own
                        // "hi is a soft bound" doc). Jumping only to declaredEnd here would leave
                        // that tail's indices still inside this ManualC's [start, end) span, which
                        // the swallow fallback below would then re-swallow — or, worse, re-render as
                        // duplicate plain rows once the collapsed-vs-expanded distinction is added.
                        idx = manualEffectiveEnd(mr)
                    } else {
                        // Collapsed: hide only this block's OWN declared interior. A hosted crossing
                        // sequence's extra tail beyond declaredEnd is NOT this block's content — it
                        // belongs to the guest, whose own header sits inside the now-hidden declared
                        // range and so can't show either; that tail falls through the swallow
                        // fallback below (bounded by declaredEnd, not the stretched `.end`) and
                        // renders as plain rows instead of silently vanishing.
                        idx = child.declaredEnd
                    }
                }

                stackGroupByRid[entry.id] != null -> {
                    val stg = stackGroupByRid.getValue(entry.id)
                    val exp = stg.gid in tab.expanded
                    items += LogItem.StackTraceHeader(entry, stg.gid, indent, exp, stg.memberIds.size)
                    // Mark members as owned by an emitted header regardless of `exp` — a collapsed
                    // header's count already accounts for them, and expanding it later must be able
                    // to reveal them via this same bitset.
                    stg.memberIds.forEach(membersUnderEmittedStackHeader::set)
                    if (exp) {
                        stg.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, indent + 1, DANGER_RED) } }
                    }
                    idx += 1
                }

                nestedStackGroupByRid[entry.id] != null -> {
                    val stg = nestedStackGroupByRid.getValue(entry.id)
                    val exp = stg.gid in tab.expanded
                    items += LogItem.StackTraceHeader(entry, stg.gid, indent, exp, stg.memberIds.size)
                    // See the sibling stackGroupByRid branch above: mark unconditionally, not only
                    // when exp.
                    stg.memberIds.forEach(membersUnderEmittedStackHeader::set)
                    if (exp) {
                        stg.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, indent + 1, DANGER_RED) } }
                    }
                    idx += 1
                }

                childPtr < children.size && idx >= children[childPtr].start && idx < swallowBoundFor(children[childPtr]) -> {
                    // Covered by the current child's own declared interior while COLLAPSED (an
                    // EXPANDED child instead jumps `idx` straight past its range, so this branch is
                    // only ever reached for a collapsed one — see the SeqC/NestedC/ManualC branches
                    // above). A collapse hides only what the child actually owns: for a thread-scoped
                    // SeqC/NestedC, an entry on a foreign tid was never part of its run (same
                    // childIds exclusion SeqComputer applies to the group's reported count), so it
                    // must still render here — at THIS level's own indent/color, i.e. exactly the
                    // fallback a plain row at this level already gets in the `else` branch below.
                    val c = children[childPtr]
                    val cScopeTid = when (c) {
                        is ChildRef.SeqC -> defMap[c.sg.defId]?.scopeTid
                        is ChildRef.NestedC -> defMap[c.ng.defId]?.scopeTid
                        is ChildRef.ManualC -> null // manual blocks are never thread-scoped
                    }
                    if (cScopeTid != null && entry.tid != cScopeTid) {
                        items += LogItem.Row(entry, indent, ambientColor)
                    }
                    idx += 1
                }

                // Skip only if this member's OWN group header was actually emitted above — not
                // merely because the entry belongs to some stack-trace group. A header can be
                // pre-empted by a container branch starting at the same index (SeqC/NestedC/ManualC
                // above) or jumped over entirely by a collapsed ManualC's jump to declaredEnd; in
                // either case membersUnderEmittedStackHeader was never set for this id, so the
                // member falls through to the `else` branch below and renders as a plain row at the
                // current level instead of vanishing with no header left to reveal it.
                membersUnderEmittedStackHeader.get(entry.id) -> idx += 1 // stack-trace member row, shown only under its emitted header

                else -> {
                    // scopeTid/foreignIndent/foreignColor (only non-null/non-default when this call
                    // is directly walking a thread-scoped ancestor's EXPANDED interior — see the doc
                    // on renderRange's parameters) route a foreign-tid entry to the ENCLOSING level
                    // instead of nesting/tinting it as part of the run it doesn't belong to.
                    if (scopeTid != null && entry.tid != scopeTid) {
                        items += LogItem.Row(entry, foreignIndent, foreignColor)
                    } else {
                        // scopeTid != null here means entry.tid == scopeTid (the branch above
                        // already peeled off the mismatch case) — a genuine member of the
                        // thread-scoped sequence whose expanded interior this recursion is
                        // directly walking, at exactly the color that member's own row already
                        // renders with. Carried separately so LogRow can tint ts/pid/tid too,
                        // without confusing it for an ordinary/unscoped sequence's member (see
                        // LogItem.Row.scopedSeqColor's own doc).
                        val scopedColor = if (scopeTid != null) ambientColor else null
                        items += LogItem.Row(entry, indent, ambientColor, scopedColor)
                    }
                    idx += 1
                }
            }
        }
        return items
    }

    val topChildren = (
        topLevelSeqGroups.map { sg -> ChildRef.SeqC(sg, rootIdxOf(sg.rid)) } +
            topLevelManual.map { m -> ChildRef.ManualC(m, m.range.last + 1, manualEffectiveEnd(m)) }
    ).sortedBy { it.start }

    val result = renderRange(0, data.size, indent = 0, ambientColor = null, children = topChildren)
    storeCache(result)
    return result
}

private fun sourcePrefixLabel(settings: AppSettings): String =
    settings.annotationPrefixLabel.trim().ifBlank { "From" }

private fun annotationLineContext(tab: LogTab, settings: AppSettings): LogLinePresentationContext =
    LogLinePresentationContext(tab, settings, visibleEntries(tab))

// Extracted out of buildMd() purely to keep that function's cyclomatic complexity under the
// detekt gate — same behavior as when it was inlined in the AnnBlock.LogRef branch. Returns the
// next block number (only advanced when numbering is on, mirroring the original inline
// `blockNumber++` which was itself gated on settings.numberAnnotationBlocks).
private fun StringBuilder.appendLogRefBlock(tab: LogTab, settings: AppSettings, block: AnnBlock.LogRef, blockNumber: Int): Int {
    if (settings.numberAnnotationBlocks) append("$blockNumber. ")
    if (block.caption.isNotBlank()) {
        appendLine(block.caption); appendLine()
    } else if (settings.numberAnnotationBlocks) {
        appendLine()
    }
    if (block.sourceFilename != null) appendLine("${sourcePrefixLabel(settings)} ${block.sourceFilename}")
    val rows = block.resolveRows(tab)
    // A recovered/cross-tab LogRef has no reliable current viewer baseline or process-name map.
    // It still copies its own PID/TID data, but deliberately falls back to numeric PID and omits Δt.
    val localSource = block.sourceTabId == null && rows.all { tab.rmap[it.id] == it }
    val context = if (localSource) annotationLineContext(tab, settings) else null
    when (settings.annotationLogBlockStyle) {
        AnnotationLogBlockStyle.INDENTED ->
            rows.forEach { row -> appendLine("    ${presentLogLine(tab, row, settings, context, allowProcessName = localSource)}") }

        AnnotationLogBlockStyle.JIRA_JAVA -> {
            appendLine("{code:java}")
            rows.forEach { row -> appendLine(presentLogLine(tab, row, settings, context, allowProcessName = localSource)) }
            appendLine("{code}")
        }
    }
    appendLine()
    return if (settings.numberAnnotationBlocks) blockNumber + 1 else blockNumber
}

/**
 * A diagram note's export form, which differs sharply by target — this is the whole reason
 * [AnnotationLogBlockStyle] reaches into diagram rendering at all.
 *
 * IMAGE (the default): emit an attachment reference. This is portable across Markdown/Jira
 * renderers and matches the PNG AppState writes alongside the export.
 *
 * SOURCE: retain the previous dialect-source behavior — Mermaid/PlantUML fences in Markdown and
 * a Jira `{code}` block. Source mode intentionally does not reference or write a PNG.
 *
 * The spec/model header is stripped in both cases: it's machine state for reopening the note (see
 * diagram3/Seq3Codec.kt), and would otherwise show up as a stray HTML comment — or, in Jira,
 * as a wall of raw JSON.
 */
private fun StringBuilder.appendDiagramNote(
    diagram: ParsedSeq3,
    settings: AppSettings,
    diagramOrdinal: Int,
    frameStamp: String?,
) {
    // ParsedSeq3.source is the fence BODY (the header and the ``` lines are already parsed off),
    // so the Markdown form re-wraps it rather than stripping anything.
    val fenceLanguage = when (diagram.dialect) {
        Seq3Dialect.MERMAID -> "mermaid"
        Seq3Dialect.PLANTUML -> "plantuml"
    }
    if (diagram.caption.isNotBlank()) appendLine(diagram.caption)
    when (diagram.exportMode) {
        DiagramExportMode.IMAGE -> when (settings.annotationLogBlockStyle) {
            AnnotationLogBlockStyle.INDENTED -> {
                val fileName = annotationDiagramFileName(diagramOrdinal, frameStamp)
                appendLine("![${diagram.caption.ifBlank { "Sequence diagram" }}]($fileName)")
            }

            AnnotationLogBlockStyle.JIRA_JAVA ->
                appendLine("!${annotationDiagramFileName(diagramOrdinal, frameStamp)}!")
        }

        DiagramExportMode.SOURCE -> when (settings.annotationLogBlockStyle) {
            AnnotationLogBlockStyle.INDENTED -> {
                appendLine("```$fenceLanguage")
                appendLine(diagram.source.trimEnd('\n'))
                appendLine("```")
            }

            AnnotationLogBlockStyle.JIRA_JAVA -> {
                appendLine("{code}")
                appendLine(diagram.source.trimEnd('\n'))
                appendLine("{code}")
            }
        }
    }
}

// No Markdown/data-URI image is ever embedded here — it won't render in Jira plain text, and a
// bare clipboard paste can only carry one image at a time anyway. Two-step workflow instead:
// "Export frames" (AppState.exportAnnotationFrames) writes each image as frame-0N.jpg next to a
// sibling .md of this exact text, then the user pastes the text and attaches the files. Under
// JIRA_JAVA style, the marker line below is a `!frame-0N.jpg!` wiki anchor Jira Server/DC renders
// inline once the same-named file is attached — a bare Copy without exporting first yields anchors
// with no attachment behind them yet. JIRA_JAVA is the only style shown these anchors (Jira Cloud
// users on Indented would otherwise see broken `!filename!` syntax); INDENTED keeps the older
// plain-text `[screenshot]` marker.
fun buildMd(tab: LogTab, settings: AppSettings = AppSettings()): String = buildString {
    if (tab.annotations.prefix.isNotBlank()) {
        appendLine(tab.annotations.prefix); appendLine()
    }
    var blockNumber = 1
    // Counts AnnBlock.Image blocks only, independent of blockNumber above (which also counts
    // Note/LogRef blocks and is gated on numberAnnotationBlocks) — must match the ordinal (and the
    // same tab.annotations.frameStamp) AppState.writeAnnotationFrameImages()/exportAnnotationFrames()
    // assign the same images, via the shared annotationImageFileName() helper, or the anchors below
    // would reference files that don't exist (or exist under a different, unstamped/stamped name).
    var imageOrdinal = 0
    // Counts diagram notes only, on its own sequence — see annotationDiagramFileName's doc for why
    // diagrams and screenshots don't share one ordinal.
    var diagramOrdinal = 0
    for (block in tab.annotations.blocks) {
        when (block) {
            is AnnBlock.Note -> {
                if (block.text.isNotBlank()) {
                    if (settings.numberAnnotationBlocks) append("${blockNumber++}. ")
                    val diagram = parseSeq3Note(block.text)
                    if (diagram != null) {
                        diagramOrdinal += 1
                        appendDiagramNote(diagram, settings, diagramOrdinal, tab.annotations.frameStamp)
                    } else {
                        appendLine(block.text)
                    }
                    appendLine()
                }
            }

            is AnnBlock.LogRef -> blockNumber = appendLogRefBlock(tab, settings, block, blockNumber)

            is AnnBlock.Image -> {
                imageOrdinal += 1
                if (settings.numberAnnotationBlocks) append("${blockNumber++}. ")
                if (block.caption.isNotBlank()) appendLine(block.caption)
                block.displayProvenance?.let { appendLine(it) }
                when (settings.annotationLogBlockStyle) {
                    AnnotationLogBlockStyle.JIRA_JAVA ->
                        appendLine("!${annotationImageFileName(imageOrdinal, block.format, tab.annotations.frameStamp)}!")
                    AnnotationLogBlockStyle.INDENTED -> appendLine("[screenshot]")
                }
                appendLine()
            }
        }
    }
    if (tab.annotations.suffix.isNotBlank()) {
        appendLine("---"); appendLine(); append(tab.annotations.suffix)
    }
    // Keep attribution outside the note body so a copied/exported analysis makes its origin
    // clear without changing any annotation block's numbering or attachment anchors. This is
    // intentionally unconditional: even an empty analysis should identify its generating tool.
    appendAnalysisAttribution(settings.annotationLogBlockStyle)
}
