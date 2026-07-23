package com.openlog.utils

import androidx.compose.ui.graphics.Color
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.TidMapTarget
import com.openlog.model.entry
import com.openlog.ui.SEQ_COLORS

// ── Pure core (no Compose-runtime dependency — androidx.compose.ui.graphics.Color is a plain
// value holder with no composition/UI-thread coupling, same as Model.kt's own use of Color in
// Highlighter/SequenceDef — so this stays unit-testable without a harness, same split as
// utils/SeqComputer.kt / utils/StackTraceComputer.kt / this session's utils/LogTime.kt and
// ui/Minimap.kt's own pure-core section). ──

// Scoped to the target's PID alone, not the exact (pid, tid) pair — a tid map shows one PROCESS
// and its own threads (any tid belonging to that pid), not one specific thread plus whatever else
// happened to be interleaved in time from other processes. [TidMapTarget] still carries the tid of
// the row that was right-clicked (used for the "already open for this exact row" toggle check in
// AppState.toggleTidMap), but span/branch matching only ever looks at pid.
private fun matchesTidMapTarget(entry: LogEntry, target: TidMapTarget): Boolean =
    entry.pid == target.pid

/** First-to-last index in [items] (this panel's own current, filtered/folded item list — NOT the
 *  full tab.logData) whose entry's pid is [target]'s pid — any tid. Null when the process has no
 *  occurrence in this panel's current view — e.g. filtered out, or a stale target left over from a
 *  filter change. Independent per panel by construction: callers pass each panel's own `items`, so
 *  Original and Filtered naturally get different spans for the same target when filtering changes
 *  what "first/last visible" means for one but not the other. */
fun findTidMapSpan(items: List<LogItem>, target: TidMapTarget): IntRange? {
    var first = -1
    var last = -1
    for (i in items.indices) {
        if (matchesTidMapTarget(items[i].entry, target)) {
            if (first < 0) first = i
            last = i
        }
    }
    return if (first < 0) null else first..last
}

// LogItem.ManualHeader has no indent field (see model/Model.kt) — matches ui/Minimap.kt's own
// indentOrZero precedent for the same variant, not duplicated logic drifting from it by accident,
// just the same one-off fact about the sealed class restated where this file needs it too (the
// existing helper is private to ui/Minimap.kt, not exposed for reuse across the module boundary).
private fun LogItem.tidMapIndentOrZero(): Int = when (this) {
    is LogItem.Row -> indent
    is LogItem.SeqHeader -> indent
    is LogItem.ManualHeader -> 0
    is LogItem.StackTraceHeader -> indent
}

/** One drawable branch of the tid-map gutter overlay (ui/TidMap.kt) — one per row in [span].
 *  [colorKey] is [entry.tid][com.openlog.model.LogEntry.tid] — distinct THREADS of the target's
 *  process get distinct colors from each other (see [computeTidMapColors]), not just one flat
 *  color for the whole process. It's a plain [Int], not literally named "tid", so ui/TidMap.kt's
 *  click-highlight and color lookups stay generic over "whichever key this branch's color is
 *  grouped by" rather than hardcoding that it happens to be a tid today. */
data class TidMapBranch(
    val entryId: Int,
    val indent: Int,
    val colorKey: Int,
)

/** One branch per row in [span] that belongs to [target]'s own pid — [span] is the first-to-last
 *  *positional* range spanning the process's occurrences (see [findTidMapSpan]), but other
 *  processes' rows routinely fall between two occurrences of this one and must NOT get a branch:
 *  this is a single-process map, not "everything that happened nearby".
 *
 *  An earlier version of this map showed every row in the span regardless of pid, colored per-pid
 *  (plus a main-thread-of-a-different-pid override) so another process's activity stood out. That
 *  cross-process design was dropped in favor of a single-process view — but within that one
 *  process, [TidMapBranch.colorKey] still differentiates its own THREADS from each other, so which
 *  tid logged a given row is still visible at a glance, just no longer mixed in with other
 *  processes' activity. */
fun computeTidMapBranches(items: List<LogItem>, span: IntRange, target: TidMapTarget): List<TidMapBranch> =
    span.mapNotNull { i ->
        val entry = items[i].entry
        if (entry.pid != target.pid) return@mapNotNull null
        TidMapBranch(
            entryId = entry.id,
            indent = items[i].tidMapIndentOrZero(),
            colorKey = entry.tid,
        )
    }

/** Assigns a [Color] to every distinct [TidMapBranch.colorKey] in [branches] (in first-appearance
 *  order within the span) that [existing] doesn't already have one for, cycling [palette] and
 *  skipping colors already in use — the same "cycle the palette, skip colors already in use, wrap
 *  once exhausted" idiom AppState.nextSequenceColor/nextAvailableHighlighterColor already use
 *  (AppState.kt), expressed as a pure function over a List/Map instead of AppState's own mutable
 *  fields, so it's usable from a background thread (see AppState.toggleTidMap's off-thread
 *  computation) and unit-testable directly.
 *
 *  [palette] defaults to [SEQ_COLORS] (what every test in this file uses, and what this function
 *  used unconditionally before themes entered the picture) but the real app passes
 *  `[ac, seq2, seq1]`[com.openlog.ui.ThemeColors] instead (see AppState.toggleTidMap) — `ac` first
 *  because it's the field that actually varies most from theme to theme (an earlier version used
 *  seq1/seq2 alone, but 15 of this app's 20 themes keep seq1 in the same purple/violet family, so
 *  the map barely looked different across themes).
 *
 *  [existing] lets a caller preserve colors already assigned in an earlier pass — AppState.
 *  toggleTidMap uses this to guarantee the specific tid that was right-clicked gets the palette's
 *  FIRST (most prominent) color, rather than whichever tid happens to appear earliest in the span
 *  positionally — those are usually the same row but aren't guaranteed to be. */
fun assignTidMapColors(
    branches: List<TidMapBranch>,
    existing: Map<Int, Color> = emptyMap(),
    palette: List<Color> = SEQ_COLORS,
): Map<Int, Color> {
    val assigned = existing.toMutableMap()
    val used = existing.values.toMutableSet()
    val seenKeys = LinkedHashSet<Int>()
    branches.forEach { seenKeys.add(it.colorKey) }
    for (key in seenKeys) {
        if (key in assigned) continue
        val candidate = palette.firstOrNull { it !in used }
            ?: palette[assigned.size % palette.size]
        assigned[key] = candidate
        used += candidate
    }
    return assigned
}

/** The tid→Color palette for a whole tid map, computed ONCE from [logData] — the tab's full,
 *  unfiltered entry list, NOT any one panel's own filtered/folded item list — and stored in
 *  [TidMapState.colors][com.openlog.model.TidMapState] by AppState.toggleTidMap so every panel's
 *  TidMapOverlay reads the exact same assignment instead of each deriving its own.
 *
 *  Why the source has to be [logData] and not a panel's `items`: color assignment walks tids in
 *  first-appearance order WITHIN THE SPAN, and "first appearance" is only a single well-defined
 *  order when there's a single canonical row ordering to walk. Original and Filtered panels can
 *  (and routinely do) show different subsets of rows, so "first appearance" against one panel's
 *  own items is not the same order as against the other's — that was the actual bug (originally
 *  discovered at the pid level, before the map became process-scoped): the same key could land on
 *  two different colors depending which panel you were looking at. `logData` is the one ordering
 *  both panels ultimately derive from, so anchoring here is what makes the assignment actually
 *  canonical.
 *
 *  [target]'s own tid is seeded to [palette]'s first entry before the general assignment runs (see
 *  [assignTidMapColors]'s `existing` param) — the thread that was actually right-clicked always
 *  gets the most prominent color, regardless of where it falls in first-appearance order.
 *
 *  [palette] — see [assignTidMapColors]'s own doc for why the real app passes theme-derived colors
 *  here instead of the [SEQ_COLORS] default.
 *
 *  Deliberately reuses [findTidMapSpan]/[computeTidMapBranches]/[assignTidMapColors] wholesale
 *  (wrapping each [LogEntry] as a throwaway indent-0 [LogItem.Row]) rather than a parallel
 *  entry-level implementation of the same span/palette rules — one source of truth for what "first
 *  appearance" and "which tid does this row's branch count toward" mean, not two. */
fun computeTidMapColors(logData: List<LogEntry>, target: TidMapTarget, palette: List<Color> = SEQ_COLORS): Map<Int, Color> {
    val items = logData.map { LogItem.Row(it, indent = 0) }
    val span = findTidMapSpan(items, target) ?: return emptyMap()
    val branches = computeTidMapBranches(items, span, target)
    val seed = palette.firstOrNull()?.let { mapOf(target.tid to it) } ?: emptyMap()
    return assignTidMapColors(branches, existing = seed, palette = palette)
}

/** Which sub-range of the currently-visible branches shares [highlightedColorKey] — the (first,
 *  last) `entryId` pair the spine's own highlighted-color overlay segment (ui/TidMap.kt) should
 *  span, per the "spine shows the highlighted color's own sub-range" feature. [visibleBranches]
 *  must already be in on-screen top-to-bottom order (see TidMapOverlay's own `visible` list,
 *  sorted by each row's actual laid-out Y) — this function doesn't re-sort, it just picks the
 *  first and last matching entries in whatever order it's given, so a caller passing an unsorted
 *  list would silently get a wrong (but not crashing) sub-range; that ordering contract lives with
 *  the caller because only the caller has genuine on-screen Y positions to sort by, this file's
 *  pure core never does.
 *
 *  Null when nothing in [visibleBranches] matches — e.g. the highlighted group has scrolled fully
 *  out of view, or [highlightedColorKey] doesn't (yet, or any longer) appear on screen — the caller
 *  simply skips drawing the overlay segment in that case. */
fun tidMapHighlightedEntryRange(visibleBranches: List<TidMapBranch>, highlightedColorKey: Int): Pair<Int, Int>? {
    val matching = visibleBranches.filter { it.colorKey == highlightedColorKey }
    if (matching.isEmpty()) return null
    return matching.first().entryId to matching.last().entryId
}
