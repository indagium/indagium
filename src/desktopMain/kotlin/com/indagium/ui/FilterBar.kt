@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.RuleTarget
import com.indagium.utils.isValidRegexPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// ── Horizontal filter bar — exploratory v1 ──────────────────────────────────────────────────────
//
// A second RENDERING of the same Filter object FilterPanel.kt already edits — not a new encoding.
// Every pill here is the same Set<String>/List<MessageRule> the panel reads, and every mutation
// goes through the exact same AppState methods (FilterBarActions is just a narrower lambda bag
// over them, following the LogCompositionActions precedent — see FilterPanel.kt's own doc on that
// class). There is exactly one filter engine; this file only draws a different view of it.
//
// ── Mutual exclusion (the invariant everything below rests on) ──────────────────────────────────
// FilterBar composes ⟺ state.filterVisible == false ⟺ BoundFilterPanel returned early
// (ui/FileView.kt's own early `if (!state.filterVisible) return`) ⟺ FilterPanel is not in the tree.
// FilterPanel.kt's debounced write-through (its kwDisplay/kwLastSent and msgRuleInput/
// msgRuleLastSent pairs, FilterPanel.kt:719-727 and :745-762) assumes a SINGLE writer: it tracks
// "the last value I pushed" so it can tell its own debounce settling apart from an external change
// and never re-syncs a value it just wrote itself back into the field being typed in. Two
// simultaneous writers (this bar and the panel both mounted) would each hold their own timer and
// sentinel; the two debounces would race and a stale value could get pushed back into whichever
// field the user is still typing in. Mutual exclusion removes that whole bug class structurally —
// this bar can reuse the exact same sentinel shape below because it is, by construction, the only
// writer while it is composed.
//
// Accepted loss: pressing Shift+F mid-debounce (within ~350ms of the last keystroke, largeFileMode)
// discards up to that much just-typed, not-yet-applied text along with this composable itself. A
// DisposableEffect flush on unmount was considered and rejected: it would fire concurrently with
// FilterPanel's own mount-time seeding (it reads `filter.kwText`/`filter.kwInTag` once, on first
// composition) — i.e. exactly the two-writer race this design avoids everywhere else. A half-typed
// filter losing its last keystroke on a deliberate panel-visibility toggle is a small, honest
// tradeoff next to reintroducing that race.
//
// ── Focus rule (the CLAUDE.md "a dismissed Popup steals focus" trap) ────────────────────────────
// This bar sits directly above a LogViewer that owns root key handling (arrows move selection,
// Enter expands a group, etc.), and every click target here is a Modifier.clickable — each one of
// which is focusable by default and will silently steal keyboard focus from the log on click,
// exactly the trap CLAUDE.md documents. The rule, applied to every click in this file:
//   - a click that ENDS an interaction (switch mode, toggle .*, open the panel via the residual
//     chip) returns focus to the log via [logFocusRequester].
//   - a click that CONTINUES one (pick a candidate, pick a history entry, clear a field, open/
//     close a field's pill dropdown, remove a pill from that dropdown) returns focus to that
//     field's own FocusRequester — see [TagFieldBadge]/[MessageFieldBadge] below: their dropdown
//     is anchored to, and conceptually part of, the field it trails, so a pill click there is a
//     continuation of editing that field, not a departure from the bar.
// There is no third case. Every requestFocus() call is wrapped in runCatching — it throws if the
// requester isn't attached to a composition yet (e.g. the log hasn't laid out on the very first
// frame). There is deliberately no root-level onPreviewKeyEvent on this bar's own Column: keys
// belong to the individual fields (which each handle their own Escape/arrows/Enter) or to the log
// underneath, never to a bar-wide interceptor.
//
// ── Popups, not inline dropdowns ─────────────────────────────────────────────────────────────────
// The tag/pkg candidate list, the regex-history list, and each field's own trailing-badge pill
// dropdown ([TagFieldBadge]/[MessageFieldBadge]) all render in a
// Popup(properties = PopupProperties(focusable = false)) wrapped in DisableSelection — same
// precedent as ui/AiSidebar.kt's account-profile picker (:999-1016; see that file for why
// DisableSelection is needed: a Popup's content still registers with the ambient
// SelectionContainer even though its LayoutCoordinates live in a separate root, and starting a
// text-selection drag across that boundary throws). `focusable = false` is load-bearing twice
// here: (1) the field itself keeps keyboard focus while the popup is open, so arrows/Enter/Escape
// keep reaching the field's own handler instead of the popup stealing them, and (2) the bar's own
// height never changes when a popup opens — an inline dropdown would push the log list down by as
// much as 220dp on every keystroke.
//
// ── Why not describeFilter (cases/CaseModel.kt:175) ─────────────────────────────────────────────
// describeFilter renders a compact one-line summary of tags/keyword/rules for the Case Library's
// note preview. It looks like a shortcut for the residual-fields chip below, but it is the wrong
// function: it reports activeTags/kwText/messageRules — exactly what this bar ALREADY renders as
// pills and fields — so reusing it would double-report those fields in the chip, and it omits
// highlighters entirely (never mentions them at all). [filterBarResidualSummary] below is a
// separate, narrower function that reports ONLY what this bar does not already show as a pill or a
// field: levels, highlighters, excludeKw, pidTidFilter, and inert (wrong-mode) message rules.
// Sequences are deliberately excluded from this list — see the "Honesty" section below.
// FilterBarTest's negative test pins this: it fails the day someone "simplifies" this chip back
// to describeFilter.
//
// ── Scope (record explicitly) ───────────────────────────────────────────────────────────────────
// In: both filter-mode representations (Tags: tag/pkg field + message field, each with a trailing
// count badge that opens a removable-pills dropdown anchored to it; Regex: pattern field +
// persisted last-50 history with autocomplete); its own mode toggle (essential — with the panel
// hidden there is no other way to switch filter.mode); single-tab view only. The tag field and the
// message-rule field are held to FULL parity with FilterPanel.kt's own — including the message-rule
// *scope chooser* (rendered as a Popup anchored to the message field here instead of the panel's
// inline block, but with identical prompt/options/keyboard handling/commit semantics — see
// MessageRuleField's own scope-chooser state below) and the contextual-candidate immediate-commit
// path (`MsgCandidate.addsImmediately`).
// Out: any FilterPanel.kt refactor; compare mode (CompareView.kt keeps passing null for this bar,
// which is what keeps compare mode bar-free — see FileView.kt/CompareView.kt call sites); keyboard
// roving inside the bar (FilterPanel's Alt+Up/Down "filter target" roving has no equivalent here,
// since the bar has no sequences/saved-filters/levels lists to rove over); a CtrlFTarget destination
// for this bar; drag gestures; an AppSettings.defaultFilterMode.
//
// ── Honesty ──────────────────────────────────────────────────────────────────────────────────────
// This bar shows tags, package prefixes (included/excluded), message rules (Tags-mode only, both
// directions), and its own two input fields. It does NOT show `levels`, `highlighters`,
// `excludeKw`, `pidTidFilter`, or KEYWORD-mode-authored message rules while in Tags mode (they're
// preserved but inert — see MessageRule.mode's own doc). Whenever any of those is non-default,
// [filterBarResidualSummary] renders a single clickable "+ …" chip summarizing them; clicking it
// opens the full panel (an "ends an interaction" click — see the focus rule above). `sequences`
// is a separate case: this bar never reports it at all, in a pill or in the chip — by design, not
// oversight (the user does not want sequence info surfaced in this bar).

/**
 * Everything the bar needs beyond what [LogViewer] already threads through as `tab` — AppState-only
 * bits FilterBar can't derive from the tab/filter alone. A plain data class of values (not
 * lambdas), so structural equality keeps [LogViewer] skippable across recompositions that don't
 * actually change any of these — see the `LogCompositionActions` doc in FilterPanel.kt for the
 * same "values vs lambdas get two different parameter shapes" reasoning.
 */
data class FilterBarModel(
    val sortedTags: List<String>,
    val tagUsage: Map<String, Int>,
    val mostUsedTagLimit: Int,
    val regexHistory: List<String>,
)

/**
 * Lambdas into the exact same [AppState] methods FilterPanel.kt's own callback parameters call —
 * see that file's `FilterPanel(...)` signature for the full-featured versions this is a narrower
 * subset of. Grouped into one bag (rather than a dozen more parameters on [LogViewer], which
 * already has 42) following the `LogCompositionActions` precedent; MUST be `remember`ed by the
 * caller (FileView.kt) since — unlike [FilterBarModel] — every field here is a lambda, and a
 * freshly allocated bag on every recomposition would defeat LogViewer's skippability the same way
 * a fresh lambda parameter would.
 */
data class FilterBarActions(
    val onSetFilterMode: (FilterMode) -> Unit,
    val onStartRegexSearch: () -> Unit,
    val onToggleTag: (String) -> Unit,
    val onToggleExcludeTag: (String) -> Unit,
    val onAddPkgPrefix: (String) -> Unit,
    val onRemovePkgPrefix: (String) -> Unit,
    val onAddExcludePkgPrefix: (String) -> Unit,
    val onRemoveExcludePkgPrefix: (String) -> Unit,
    val onSetKwInTag: (String) -> Unit,
    val onToggleKwInTagRegex: () -> Unit,
    val onSetKw: (String) -> Unit,
    // (include, pattern, regex, tag, packagePrefix, target) — same shape and same underlying
    // AppState.addMessageRule call as FilterPanel.kt's own onAddMessageRule (see BoundFilterPanel
    // in ui/FileView.kt). tag/packagePrefix carry the scope chosen via MessageRuleField's own scope
    // chooser (Popup-presented port of FilterPanel.kt:1467-1595) or, for a contextual candidate,
    // its own tag — both null commits an unscoped "All" rule, same as the panel's messageRuleAllScope().
    val onAddMessageRule: (Boolean, String, Boolean, String?, String?, RuleTarget) -> Unit,
    val onRemoveMessageRule: (String) -> Unit,
    val onRememberRegexPattern: (String) -> Unit,
    val onClearRegexHistory: () -> Unit,
    val onOpenFilterPanel: () -> Unit,
)

// Below this bar width, Tags mode stacks the tag/pkg field and the message field onto two rows
// instead of splitting one row between them — same "narrow window" concern FilterPanel.kt's own
// FILTER_PANEL_MIN_WIDTH (140dp) addresses for the vertical panel, just for a horizontal one that
// can be squeezed by the annotation/AI sidebars instead of dragged directly.
private const val NARROW_BAR_WIDTH_DP = 560

// Fix (height parity between Tags/Regex modes): the bar's own input row — the one row that hosts
// the mode switcher plus whichever fields the current mode shows — used to size itself from its
// tallest child plus each mode's own ad-hoc vertical padding (Tags' wide row: 2.dp; Regex's row:
// 4.dp). Those two diverged just enough — one taller field plus 2dp less padding versus the
// other's shorter controls plus 2dp more — that switching filter.mode visibly nudged the whole log
// list up or down by a few px. A single shared container (matching SearchBar.kt's own effective
// row height so the two stacked bars read as siblings) removes the possibility of the two ever
// drifting apart again: every mode's own input row is built by calling this, never by hand-rolling
// its own Row.
//
// This is `heightIn(min=)`, not `height()`. Two properties, both needed:
//  - The floor keeps a small-font row (AppSettings.fontSize down at 10, SettingsDialog.kt:453)
//    from looking cramped next to the Find bar it sits above.
//  - `heightIn(min=)` rather than `height()` is what keeps a LARGE AppSettings.fontSize (the
//    stepper goes up to 24, SettingsDialog.kt:453-457) from clipping the row's fields. InlineField
//    (Components.kt:688) defaults its font to `LocalFontBase.current.sp`, which follows that same
//    setting, and adds its own `padding(vertical = 4.dp)` on top of the text line
//    (Components.kt:727) — at the top of the 10..24 range that combination is taller than this
//    row's fixed 26dp content area (34dp minus this row's own 2×4dp vertical padding) would allow,
//    so a fixed `height()` here would clip the text exactly the way `BoundedScrollBoxDp`'s own
//    comment on this mistake warns against (Components.kt:398-401: "heightIn(max) rather than
//    height(): … a fixed height clips real content that's taller than the guess"). `heightIn(min=)`
//    keeps the height-parity guarantee above intact: the tallest child in both Tags and Regex mode
//    is an InlineField carrying that same font-scaled size, so at any font size both modes grow by
//    the same amount past the floor and stay equal to each other.
private val FILTER_BAR_ROW_MIN_HEIGHT = 34.dp

@Composable
private fun FilterBarInputRow(
    horizontalArrangement: Arrangement.Horizontal,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FILTER_BAR_ROW_MIN_HEIGHT)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

// A subtle separator between the tag/pkg field and the message field in Tags mode's wide layout —
// without their own InlineField chrome (see Fix 1's `flat` param), the two fields would otherwise
// visually run together into what reads as one field. Chosen over just widening the gap because a
// hairline reads as "two distinct fields" at a glance the way extra whitespace alone does not.
@Composable
private fun FilterFieldDivider() {
    val tc = tc()
    Box(Modifier.width(1.dp).height(20.dp).background(tc.br))
}

// Shared popup-menu surface — exactly ExportMenuPopup/ToolbarOptionsPopup's treatment
// (LogViewer.kt) — used by both TagFieldBadge's and MessageFieldBadge's pill dropdowns so they
// read as the same idiom as the rest of the app's popups rather than FilterBar inventing its own.
private val FILTER_BAR_POPUP_SHAPE = RoundedCornerShape(7.dp)

// Fix 2a: how long after a Popup's onDismissRequest fires a badge click is treated as "the same
// gesture that just closed it" rather than a fresh open request — see TagFieldBadge/
// MessageFieldBadge's `toggle()`/`dismiss()` for where this is used. With a non-focusable Popup
// (load-bearing elsewhere in this file — see the header's "Popups, not inline dropdowns" section),
// an outside click can reach both the Popup's own dismiss handling and the badge's `clickable`
// underneath in the same gesture; without this guard that would immediately reopen whatever the
// outside click had just closed. 250ms comfortably covers a single click/dismiss round-trip
// without being long enough to swallow a deliberate second click.
private const val FILTER_BAR_REOPEN_GUARD_MS = 250L

// Only tracks the pills-badge dropdown now (NONE/PILLS) — mutual exclusion with the candidates
// popup is enforced the same way the panel enforces it (FilterPanel.kt:689-693): the candidates
// popup has its own plain `showCandidates` boolean, keyed ONLY on (fieldFocused, candidatesHovered)
// — never on this popup state — and its render site additionally checks `popupState != PILLS`.
//
// This used to be a 3-way NONE/CANDIDATES/PILLS enum with `showCandidates` derived from
// `popupState == CANDIDATES`, and the candidates-visibility LaunchedEffect keyed on
// `(fieldFocused, candidatesHovered, popupState)` — i.e. on a value the effect ALSO WROTE. Every
// write to popupState cancelled and relaunched that same effect, which is exactly the "a dismissed
// Popup steals focus" trap's sibling bug: a self-referential effect key. Splitting the two concerns
// back into "which popup is showing" (render-time, checked at each popup's `if`) and "is this popup
// open" (state, one plain boolean per concern, keyed on real external inputs only) removes the
// self-reference entirely rather than special-casing around it — see TagAndPkgField/MessageRuleField
// below for the actual `showCandidates` booleans this replaced.
//
// Hoisted once per field in TagsModeBarContent (not created inside TagFieldBadge/TagAndPkgField
// themselves) so the badge and the field it trails share the exact same state object — see that
// function's own `tagPopup`/`msgPopup`.
private enum class FilterFieldPopup { NONE, PILLS }

/**
 * Pure, testable summary of everything this bar does NOT already render as a pill or a field —
 * see the file header's "Honesty" section for the exact field list and why this is a separate
 * function from `describeFilter` (cases/CaseModel.kt:175). Empty string when every one of those
 * fields is at its default (nothing to add to what's already visible as a pill/field).
 */
internal fun filterBarResidualSummary(filter: Filter): String {
    val parts = mutableListOf<String>()
    if (filter.levels.isNotEmpty() && filter.levels != LogLevel.entries.toSet()) {
        val minOrdinal = filter.levels.minOf { it.ordinal }
        val contiguousFromMin = LogLevel.entries.filter { it.ordinal >= minOrdinal }.toSet()
        parts += if (filter.levels == contiguousFromMin) {
            "level≥${LogLevel.entries[minOrdinal].key}"
        } else {
            "levels=" + filter.levels.sortedBy { it.ordinal }.joinToString(",") { it.key.toString() }
        }
    }
    val enabledHighlighters = filter.highlighters.count { it.on }
    if (enabledHighlighters > 0) {
        parts += "$enabledHighlighters highlighter${if (enabledHighlighters == 1) "" else "s"}"
    }
    if (filter.excludeKw.isNotBlank()) parts += "excl-kw=\"${filter.excludeKw}\""
    if (filter.pidTidFilter.isNotBlank()) parts += "pid/tid=${filter.pidTidFilter}"
    // A rule authored in the OTHER mode is preserved but inert (MessageRule.mode's own doc) —
    // never counted among the pills this bar renders for the CURRENT mode.
    val inertRules = filter.messageRules.count { it.mode != filter.mode }
    if (inertRules > 0) parts += "$inertRules inert rule${if (inertRules == 1) "" else "s"}"
    return parts.joinToString(" · ")
}

/** Total pill count the bar's own Tags-mode pills row renders — package prefixes (both
 *  directions), tags (both directions), and CURRENT-mode message rules. Drives the chevron
 *  header's count and whether the pills row (and its chevron) render at all. */
internal fun filterBarPillCount(filter: Filter): Int =
    filter.pkgPrefixes.size + filter.excludePkgPrefixes.size +
        filter.activeTags.size + filter.excludeTags.size +
        filter.messageRules.count { it.mode == filter.mode }

// Async, cancellable wrapper around FilterPanel.kt's internal computeUnifiedCandidatesSync —
// same "stale but usable" shape as that file's own private rememberUnifiedCandidates (FilterPanel.
// kt:364), reimplemented here (rather than reusing the composable directly) because that composable
// is `private` to FilterPanel.kt and this file does not otherwise touch FilterPanel.kt.
// computeUnifiedCandidatesSync itself is `internal`, so calling it directly from here is the
// intended reuse seam.
@Composable
private fun rememberBarUnifiedCandidates(tab: LogTab, filter: Filter, msgRuleSearch: String): List<MsgCandidate> {
    var candidates by remember(tab.id, tab.largeFileMode) { mutableStateOf(emptyList<MsgCandidate>()) }
    LaunchedEffect(tab.id, tab.largeFileMode, filter, msgRuleSearch) {
        if (msgRuleSearch.isBlank()) {
            candidates = emptyList()
            return@LaunchedEffect
        }
        val snapshot = tab
        candidates = withContext(Dispatchers.Default) {
            computeUnifiedCandidatesSync(snapshot, filter, msgRuleSearch) { ensureActive() }
        }
    }
    return candidates
}

// Async, cancellable wrapper around FilterPanel.kt's internal computeRelevantScopeTagsSync — same
// "stale but usable" shape and null semantics (null = no scope chooser open) as that file's own
// private rememberRelevantScopeTags (FilterPanel.kt:383), reimplemented here for the same reason as
// rememberBarUnifiedCandidates above: that composable is `private` to FilterPanel.kt, while
// computeRelevantScopeTagsSync itself is `internal` — the intended reuse seam.
@Composable
private fun rememberBarRelevantScopeTags(tab: LogTab, pendingMessageRule: PendingMessageRuleDraft?): Set<String>? {
    var tags by remember(tab.id, tab.largeFileMode) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(tab.id, tab.largeFileMode, pendingMessageRule) {
        val pending = pendingMessageRule
        if (pending == null) {
            tags = null
            return@LaunchedEffect
        }
        val snapshot = tab
        tags = withContext(Dispatchers.Default) {
            computeRelevantScopeTagsSync(snapshot, pending) { ensureActive() }
        }
    }
    return tags
}

@Composable
internal fun FilterBar(
    tab: LogTab,
    model: FilterBarModel,
    actions: FilterBarActions,
    logFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val filter = tab.filter

    // The Tags/Regex switcher used to live in a shared top Row here, alongside the residual summary
    // chip. Design feedback moved the switcher onto the input-fields row itself (first element,
    // smaller — see TagsModeBarContent/RegexModeBarContent) so it sits right next to what it
    // switches, and moved the residual chip onto the same centred row as the Tags-mode pill badge
    // (TagsModeBarContent's own Box) so the badge can be truly horizontally centred without the
    // chip eating input-field width. There is deliberately no bar-wide top row left at all.
    Column(
        modifier
            .fillMaxWidth()
            .background(tc.p)
            .border(androidx.compose.foundation.BorderStroke(1.dp, tc.br)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < NARROW_BAR_WIDTH_DP.dp
            if (filter.mode == FilterMode.TAGS) {
                TagsModeBarContent(tab, filter, model, actions, narrow, logFocusRequester)
            } else {
                RegexModeBarContent(tab, filter, model, actions, logFocusRequester)
            }
        }
    }
}

/**
 * The residual-fields chip, shared by both filter-mode representations — see the file header's
 * "Honesty" section for exactly what it reports. Used to render as part of a bar-wide centred
 * summary row alongside a pill-count badge; the badge moved onto each field's own trailing end
 * (see [TagFieldBadge]/[MessageFieldBadge]) so this chip is now inlined directly into each mode's
 * input row instead — see [TagsModeBarContent]/[RegexModeBarContent] for its call sites. Renders
 * nothing at all when there is no residual, so an unfiltered tab gains no empty chip in either mode.
 */
@Composable
private fun FilterBarResidualChip(
    filter: Filter,
    actions: FilterBarActions,
    logFocusRequester: FocusRequester?,
) {
    val tc = tc()
    fun refocusLog() { runCatching { logFocusRequester?.requestFocus() } }
    val residual = remember(filter) { filterBarResidualSummary(filter) }
    if (residual.isEmpty()) return

    DisableSelection {
        Box(
            Modifier
                .hoverPill()
                .clickable {
                    actions.onOpenFilterPanel()
                    refocusLog() // opening the panel ends this bar's interaction.
                }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            AppText("+ $residual", color = tc.td, fontSize = 10.sp, fontFamily = UI)
        }
    }
}

/**
 * The tag/pkg field's trailing count badge — tag-side counts only (`N pkg`/`N pkg−`/`N+`/`N−`),
 * never the message-rule counts (those live on [MessageFieldBadge] instead, trailing the message
 * field). Renders nothing when every one of its own counts is zero, so it never reserves empty
 * width next to the field. Clicking it opens a [Popup] anchored below it, listing the same
 * removable pills the panel's own Tags section shows (FilterPanel.kt:1148-1155) — see the file
 * header's "Popups, not inline dropdowns" section for why a Popup and not an inline dropdown.
 * Every click here (open/close the dropdown, remove a pill) is "continues" per the file header's
 * focus rule: [fieldFocusRequester] is the tag/pkg field's own [FocusRequester] (hoisted by
 * [TagsModeBarContent] and shared with [TagAndPkgField]), not [logFocusRequester] — the dropdown is
 * conceptually part of the field it trails, not a separate bar-level control.
 */
@Composable
private fun TagFieldBadge(
    tab: LogTab,
    filter: Filter,
    actions: FilterBarActions,
    fieldFocusRequester: FocusRequester,
    popup: MutableState<FilterFieldPopup>,
) {
    val tc = tc()
    fun refocusField() { runCatching { fieldFocusRequester.requestFocus() } }
    val pkgColor = PKG_CYAN
    val exNeg = DANGER_RED
    var popupState by popup
    val expanded = popupState == FilterFieldPopup.PILLS
    // Fix 2a: guards the "outside click reopens the badge it just closed" race — see this file's
    // FILTER_BAR_REOPEN_GUARD_MS doc for why a non-focusable Popup needs this at all.
    var lastDismissAt by remember(tab.id) { mutableStateOf(0L) }
    fun toggle() {
        val now = System.currentTimeMillis()
        if (!expanded) {
            if (now - lastDismissAt < FILTER_BAR_REOPEN_GUARD_MS) return
            // Fix 1: opening PILLS suppresses the candidates popup — its render condition checks
            // `popupState != PILLS` (see TagAndPkgField below) — set synchronously here, before
            // refocusField() below runs, so that check is already in effect by the time the
            // refocus-triggered recomposition happens.
            popupState = FilterFieldPopup.PILLS
        } else {
            popupState = FilterFieldPopup.NONE
        }
    }
    fun dismiss() {
        popupState = FilterFieldPopup.NONE
        lastDismissAt = System.currentTimeMillis()
    }
    val hasAny = filter.pkgPrefixes.isNotEmpty() || filter.excludePkgPrefixes.isNotEmpty() ||
        filter.activeTags.isNotEmpty() || filter.excludeTags.isNotEmpty()
    // Removing the last pill from the dropdown below makes hasAny false, which makes the badge
    // itself disappear (the early return just below) — close the now-orphaned popup along with it.
    LaunchedEffect(hasAny) { if (!hasAny && expanded) popupState = FilterFieldPopup.NONE }
    if (!hasAny) return

    // Bug fix (popup covered the anchor): a Popup's `alignment` positions it WITHIN the anchor's
    // own bounds, not below them — BottomStart pinned the popup's bottom-left to the badge's
    // bottom-left, so it grew upward and covered whatever sat above the badge instead of appearing
    // below it. Measuring the badge's own height here and using TopStart + a matching downward
    // offset is what actually places the popup directly under the badge, with no magic constant.
    var badgeHeightPx by remember { mutableStateOf(0) }

    Box(Modifier.onGloballyPositioned { coords -> badgeHeightPx = coords.size.height }) {
        DisableSelection {
            Row(
                Modifier
                    // Fix 2c: keep the badge visibly "pressed" while its dropdown is open, in
                    // addition to the chevron flip below — an accent tint layered under hoverPill's
                    // own hover tint (drawn within the same pill shape, so it never bleeds past it).
                    .background(if (expanded) tc.ac.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(percent = 50))
                    .hoverPill()
                    .clickable { toggle(); refocusField() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (filter.pkgPrefixes.isNotEmpty())
                    AppText("${filter.pkgPrefixes.size} pkg", color = pkgColor, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                if (filter.excludePkgPrefixes.isNotEmpty())
                    AppText("${filter.excludePkgPrefixes.size} pkg−", color = exNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                if (filter.activeTags.isNotEmpty())
                    AppText("${filter.activeTags.size}+", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                if (filter.excludeTags.isNotEmpty())
                    AppText("${filter.excludeTags.size}−", color = exNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
            }
        }
        // badgeHeightPx > 0 (i.e. the badge has actually been laid out and measured at least once)
        // guards the first-frame case: without it, `expanded` could in principle turn true before
        // onGloballyPositioned has ever fired, and offset = IntOffset(0, 0) would render the popup
        // right back on top of the badge — exactly the bug this fix removes.
        if (expanded && badgeHeightPx > 0) {
            // Fix 2 (layout): TopStart + an offset equal to the badge's own measured height places
            // the popup directly BELOW the badge at any font size — no more BottomStart-within-
            // bounds misread and no hardcoded dp guess that only matched one row height.
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, badgeHeightPx),
                properties = PopupProperties(focusable = false),
                onDismissRequest = { dismiss(); refocusField() },
            ) {
                DisableSelection {
                    BoundedScrollBoxDp(
                        maxHeightDp = 220,
                        modifier = Modifier
                            .width(320.dp)
                            .background(tc.p, FILTER_BAR_POPUP_SHAPE)
                            .border(1.dp, tc.br, FILTER_BAR_POPUP_SHAPE),
                    ) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            filter.pkgPrefixes.forEach { pfx ->
                                TagPill(pfx, pkgColor) { actions.onRemovePkgPrefix(pfx); refocusField() }
                            }
                            filter.excludePkgPrefixes.forEach { pfx ->
                                TagPill(pfx, exNeg) { actions.onRemoveExcludePkgPrefix(pfx); refocusField() }
                            }
                            filter.activeTags.forEach { tag ->
                                TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, tc.ac, tooltip = tag) {
                                    actions.onToggleTag(tag); refocusField()
                                }
                            }
                            filter.excludeTags.forEach { tag ->
                                TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, exNeg, tooltip = tag) {
                                    actions.onToggleExcludeTag(tag); refocusField()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The message field's trailing count badge — message-rule counts only (`N msg+`/`N msg−`),
 * mirroring [TagFieldBadge] in every other respect (styling, focus handling, dropdown mechanics).
 * Only current-mode rules count/render, same as the removed inline pills row used to filter
 * (`it.mode == filter.mode` — a rule authored in Regex mode is preserved but inert here, see the
 * file header's "Honesty" section on inert rules).
 */
@Composable
private fun MessageFieldBadge(
    tab: LogTab,
    filter: Filter,
    actions: FilterBarActions,
    fieldFocusRequester: FocusRequester,
    popup: MutableState<FilterFieldPopup>,
) {
    val tc = tc()
    fun refocusField() { runCatching { fieldFocusRequester.requestFocus() } }
    val exNeg = DANGER_RED
    var popupState by popup
    val expanded = popupState == FilterFieldPopup.PILLS
    // Same reopen-race guard as TagFieldBadge above — see FILTER_BAR_REOPEN_GUARD_MS's doc.
    var lastDismissAt by remember(tab.id) { mutableStateOf(0L) }
    fun toggle() {
        val now = System.currentTimeMillis()
        if (!expanded) {
            if (now - lastDismissAt < FILTER_BAR_REOPEN_GUARD_MS) return
            // Fix 1: see TagFieldBadge's own toggle() for why this must be set before refocusField().
            popupState = FilterFieldPopup.PILLS
        } else {
            popupState = FilterFieldPopup.NONE
        }
    }
    fun dismiss() {
        popupState = FilterFieldPopup.NONE
        lastDismissAt = System.currentTimeMillis()
    }
    val rules = remember(filter) { filter.messageRules.filter { it.mode == filter.mode } }
    val hasAny = rules.isNotEmpty()
    // Same orphaned-popup guard as TagFieldBadge above.
    LaunchedEffect(hasAny) { if (!hasAny && expanded) popupState = FilterFieldPopup.NONE }
    if (!hasAny) return

    val incCount = rules.count { it.include }
    val excCount = rules.count { !it.include }

    // Bug fix (popup covered the anchor) — see TagFieldBadge's own doc on badgeHeightPx above for
    // why BottomStart-with-no-offset was wrong and why measuring the badge's height + TopStart is
    // the fix.
    var badgeHeightPx by remember { mutableStateOf(0) }

    Box(Modifier.onGloballyPositioned { coords -> badgeHeightPx = coords.size.height }) {
        DisableSelection {
            Row(
                Modifier
                    // Fix 2c: same "stay visibly pressed while open" treatment as TagFieldBadge.
                    .background(if (expanded) tc.ac.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(percent = 50))
                    .hoverPill()
                    .clickable { toggle(); refocusField() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (incCount > 0)
                    AppText("$incCount msg+", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                if (excCount > 0)
                    AppText("$excCount msg−", color = exNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
            }
        }
        // See TagFieldBadge's own doc on the badgeHeightPx > 0 guard — same first-frame concern.
        if (expanded && badgeHeightPx > 0) {
            // Fix 2 (layout): TopStart + measured-height offset, same anchoring fix as TagFieldBadge.
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, badgeHeightPx),
                properties = PopupProperties(focusable = false),
                onDismissRequest = { dismiss(); refocusField() },
            ) {
                DisableSelection {
                    BoundedScrollBoxDp(
                        maxHeightDp = 220,
                        modifier = Modifier
                            .width(320.dp)
                            .background(tc.p, FILTER_BAR_POPUP_SHAPE)
                            .border(1.dp, tc.br, FILTER_BAR_POPUP_SHAPE),
                    ) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rules.forEach { rule ->
                                val color = if (rule.include) tc.ac else exNeg
                                TagPill(messageRulePillLabel(rule), color) {
                                    actions.onRemoveMessageRule(rule.id); refocusField()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tags mode ────────────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsModeBarContent(
    tab: LogTab,
    filter: Filter,
    model: FilterBarModel,
    actions: FilterBarActions,
    narrow: Boolean,
    logFocusRequester: FocusRequester?,
) {
    fun refocusLog() { runCatching { logFocusRequester?.requestFocus() } }

    // Hoisted here (rather than created inside TagAndPkgField/MessageRuleField) so each field's own
    // trailing badge (TagFieldBadge/MessageFieldBadge) can refocus the SAME FocusRequester the field
    // itself uses — see the focus rule doc on TagFieldBadge for why a pill-dropdown click must
    // return focus to the field, not the log.
    val tagFr = remember(tab.id) { FocusRequester() }
    val msgFr = remember(tab.id) { FocusRequester() }

    // Fix 1: one popup-state per field, shared between that field's own candidate dropdown and its
    // trailing badge's pills dropdown — see FilterFieldPopup's own doc for why this must be hoisted
    // here rather than created independently inside each composable.
    val tagPopup = remember(tab.id) { mutableStateOf(FilterFieldPopup.NONE) }
    val msgPopup = remember(tab.id) { mutableStateOf(FilterFieldPopup.NONE) }

    Column(Modifier.fillMaxWidth()) {
        val switcher: @Composable () -> Unit = {
            SegmentedControl(
                options = listOf("Tags", "Regex"),
                selectedIndices = if (filter.mode == FilterMode.KEYWORD) setOf(1) else setOf(0),
                onToggle = { index ->
                    if (index == 1) actions.onStartRegexSearch() else actions.onSetFilterMode(FilterMode.TAGS)
                    refocusLog() // "ends an interaction" — see the file header's focus rule.
                },
                segmentHeight = 20.dp,
                segmentFontSize = 10.sp,
                segmentHorizontalPadding = 6.dp,
            )
        }
        val fields: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) { TagAndPkgField(tab, filter, model, actions, logFocusRequester, tagFr, msgFr, tagPopup) }
                TagFieldBadge(tab, filter, actions, tagFr, tagPopup)
            }
        }
        // Message field's own row also carries the residual "+ …" chip at its trailing end (after
        // the msg badge) — see FilterBarResidualChip's doc for why it moved here off its own row.
        val msgField: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) { MessageRuleField(tab, filter, model, actions, logFocusRequester, msgFr, msgPopup) }
                MessageFieldBadge(tab, filter, actions, msgFr, msgPopup)
                FilterBarResidualChip(filter, actions, logFocusRequester)
            }
        }
        if (narrow) {
            // Two stacked rows here is the accepted narrow-width exception (see the file's Fix-3
            // note on FILTER_BAR_ROW_MIN_HEIGHT) — Regex mode never stacks, so this width regime isn't
            // held to the "identical height across modes" requirement; each row is still built from
            // the shared container so it doesn't drift from the wide layout's own row height.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterBarInputRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    switcher()
                    Box(Modifier.weight(1f)) { fields() }
                }
                FilterBarInputRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    msgField()
                }
            }
        } else {
            FilterBarInputRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                switcher()
                Box(Modifier.weight(1f)) { fields() }
                FilterFieldDivider()
                Box(Modifier.weight(1f)) { msgField() }
            }
        }
    }
}

@Composable
private fun TagAndPkgField(
    tab: LogTab,
    filter: Filter,
    model: FilterBarModel,
    actions: FilterBarActions,
    logFocusRequester: FocusRequester?,
    fr: FocusRequester,
    // Fix 3 (Tab between fields): the message field's own FocusRequester, so Tab here can hand off
    // to it — matches the panel's tag field (FilterPanel.kt:1164: `Key.Tab -> { msgRuleFr... }`).
    nextFr: FocusRequester,
    popup: MutableState<FilterFieldPopup>,
) {
    val tc = tc()
    // Fix 3 (panel parity): unlike the other fields here, this one no longer has an Escape path
    // back to the log — see the Escape handler below's own doc — so `logFocusRequester` is kept
    // only as a parameter (for call-site consistency with the other fields) and otherwise unused.
    val pkgTagColor = PKG_CYAN // pkg candidates' +/- use the pkg color, not tc.ac — matches FilterPanel.kt:1251-1271.

    var input by remember(tab.id) { mutableStateOf("") }
    var search by remember(tab.id) { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }
    var candidatesHovered by remember { mutableStateOf(false) }
    var popupState by popup
    // Bug fix (self-referential LaunchedEffect key — see FilterFieldPopup's own doc): this is now a
    // plain boolean, keyed on real external inputs only, exactly like FilterPanel.kt's own
    // `showTagCandidates` (FilterPanel.kt:665/689-693) — never derived from `popupState`, and never
    // one of that effect's keys.
    var showCandidates by remember { mutableStateOf(false) }
    var selectedIdx by remember { mutableStateOf(-1) }
    var selectedAction by remember { mutableStateOf(0) } // 0 = include, 1 = exclude
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    // Bug fix (popup covered the field) — see TagFieldBadge's own badgeHeightPx doc for why
    // BottomStart-with-no-offset was wrong; this is the same fix applied to the field itself.
    var fieldHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(input) {
        delay(120)
        search = input
    }
    // Exact match of FilterPanel.kt:689-693's own effect — keyed ONLY on (fieldFocused,
    // candidatesHovered), writing the plain `showCandidates` boolean above. Mutual exclusion with
    // the PILLS popup is enforced separately, at the render site below (`showCandidates &&
    // popupState != PILLS`) rather than by folding PILLS into this effect's own key — see
    // FilterFieldPopup's doc for why the old 3-way-enum-as-key shape was the actual bug.
    LaunchedEffect(fieldFocused, candidatesHovered) {
        if (fieldFocused || candidatesHovered) {
            showCandidates = true
        } else {
            delay(100)
            if (!fieldFocused && !candidatesHovered) showCandidates = false
        }
    }

    val candidates = remember(model.sortedTags, search, filter.pkgPrefixes, model.tagUsage, model.mostUsedTagLimit) {
        combinedTagCandidates(model.sortedTags, search, filter.pkgPrefixes, model.tagUsage, model.mostUsedTagLimit)
    }

    // Matches FilterPanel.kt's clearTagSearch (FilterPanel.kt:1002-1007) exactly: a single-stage
    // full clear, including hiding candidates — no "close the popup first, clear on a second
    // Escape" staging (see the Escape handler below).
    fun clear() {
        input = ""
        search = ""
        selectedIdx = -1
        showCandidates = false
    }

    // Fix 2 (layout): wraps only the field itself — the Popup below is a zero-size overlay child
    // of this same Box. A Popup's `alignment` positions it WITHIN the anchor's own bounds, not
    // below them, so BottomStart pinned the popup's bottom-left to the field's bottom-left and it
    // grew UPWARD, covering the field (and whatever sat above it) instead of appearing below it.
    // Measuring the field's own height here (alongside its width, same callback) and anchoring
    // with TopStart + a matching downward offset is what actually places the popup below the
    // field, at any font size, with no magic constant.
    Box(
        Modifier.onGloballyPositioned { coords ->
            fieldWidthDp = with(density) { coords.size.width.toDp() }
            fieldHeightPx = coords.size.height
        },
    ) {
        InlineField(
            input,
            { input = it; selectedIdx = -1 },
            "pkg prefix or tag…",
            Modifier.fillMaxWidth()
                .focusRequester(fr)
                .onFocusChanged { fieldFocused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Tab -> { runCatching { nextFr.requestFocus() }; true }
                        Key.DirectionDown -> { selectedIdx = (selectedIdx + 1).coerceAtMost(candidates.lastIndex); true }
                        Key.DirectionUp -> { selectedIdx = (selectedIdx - 1).coerceAtLeast(-1); true }
                        Key.Escape -> {
                            // PILLS has no panel equivalent at all (the panel's pill list is an
                            // always-inline expandable section, not a focus-anchored popup), so
                            // closing it on Escape is a bar-only accessibility affordance, not a
                            // panel-parity concern. With PILLS not open, this is a single-stage
                            // full clear — matches FilterPanel.kt's clearTagSearch exactly, no
                            // "close the candidates popup first, clear on a second Escape" staging.
                            if (popupState == FilterFieldPopup.PILLS) {
                                popupState = FilterFieldPopup.NONE
                            } else {
                                clear()
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (candidates.getOrNull(selectedIdx) != null) { selectedAction = 1; true } else false
                        }
                        Key.DirectionLeft -> {
                            if (candidates.getOrNull(selectedIdx) != null) { selectedAction = 0; true } else false
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            val c = candidates.getOrNull(selectedIdx)
                            if (c != null) {
                                if (c.second) {
                                    if (selectedAction == 0) actions.onAddPkgPrefix(c.first) else actions.onAddExcludePkgPrefix(c.first)
                                } else if (selectedAction == 0) {
                                    actions.onToggleTag(c.first)
                                } else {
                                    actions.onToggleExcludeTag(c.first)
                                }
                            } else if (input.isNotBlank()) {
                                if (input.contains('.')) actions.onAddPkgPrefix(input) else actions.onToggleTag(input)
                            } else {
                                return@onPreviewKeyEvent false
                            }
                            selectedIdx = -1
                            selectedAction = 0
                            runCatching { fr.requestFocus() } // "continues" — Enter picks/applies, stays in the field.
                            true
                        }
                        else -> false
                    }
                },
            onClear = { clear(); runCatching { fr.requestFocus() } },
            flat = true, // Fix 1: this bar's own row carries the chrome, matching SearchBar.kt.
        )
        // popupState != PILLS is the mutual-exclusion check — see FilterFieldPopup's own doc for
        // why this lives at the render site instead of folding PILLS into showCandidates' effect.
        // fieldHeightPx > 0 guards the first-frame case: onGloballyPositioned hasn't necessarily
        // fired yet the instant the field gains focus (e.g. a freshly opened tab), and without this
        // guard the popup would render at offset = IntOffset(0, 0) — right back on top of the
        // field — for that one frame, which is exactly the bug this fix removes.
        if (showCandidates && popupState != FilterFieldPopup.PILLS && candidates.isNotEmpty() && fieldHeightPx > 0) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldHeightPx),
                properties = PopupProperties(focusable = false),
                onDismissRequest = { showCandidates = false },
            ) {
                DisableSelection {
                    Box(
                        Modifier
                            .widthIn(min = 200.dp)
                            .width(fieldWidthDp)
                            .background(tc.p, CORNER_SM)
                            .border(1.dp, tc.br, CORNER_SM),
                    ) {
                        ScrollableItems(
                            candidates.size,
                            maxDp = 220,
                            scrollToIndex = selectedIdx,
                            modifier = Modifier
                                .onPointerEvent(PointerEventType.Enter) { candidatesHovered = true }
                                .onPointerEvent(PointerEventType.Exit) { candidatesHovered = false },
                        ) {
                            candidates.forEachIndexed { idx, (value, isPkg) ->
                                val isRowSelected = idx == selectedIdx
                                val isIncluded = if (isPkg) value in filter.pkgPrefixes else value in filter.activeTags
                                val isExcluded = if (isPkg) value in filter.excludePkgPrefixes else value in filter.excludeTags
                                // Row-click semantics match the panel exactly (FilterPanel.kt:1213-1323):
                                // a pkg-prefix candidate's row commits on click; a plain-tag candidate's
                                // row does NOT — only its own +/- boxes do.
                                HoverBox(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                    baseBg = if (isRowSelected) tc.abg else Color.Transparent,
                                    hoverBg = tc.hv,
                                    onClick = if (isPkg) {
                                        {
                                            actions.onAddPkgPrefix(value)
                                            selectedIdx = -1
                                            selectedAction = 0
                                            runCatching { fr.requestFocus() } // picking a candidate continues this field.
                                        }
                                    } else {
                                        null
                                    },
                                ) {
                                    if (isPkg) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            AppText(
                                                "pkg",
                                                color = when {
                                                    isExcluded -> DANGER_RED.copy(.8f)
                                                    isIncluded -> pkgTagColor.copy(.8f)
                                                    else -> pkgTagColor.copy(.7f)
                                                },
                                                fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(26.dp),
                                            )
                                            FullTextHint(value, modifier = Modifier.weight(1f), forceShow = isRowSelected) { onTextLayout ->
                                                AppText(
                                                    value,
                                                    color = when {
                                                        isExcluded -> DANGER_RED.copy(.85f)
                                                        isRowSelected || isIncluded -> tc.tx
                                                        else -> tc.ts
                                                    },
                                                    fontSize = 11.sp, fontFamily = MONO,
                                                    modifier = Modifier.fillMaxWidth(), overflow = TextOverflow.Ellipsis, maxLines = 1,
                                                    onTextLayout = onTextLayout,
                                                )
                                            }
                                            Spacer(Modifier.width(26.dp))
                                            val incKbd = isRowSelected && selectedAction == 0
                                            Box(
                                                Modifier.size(20.dp)
                                                    .background(if (isIncluded) pkgTagColor.copy(.2f) else if (incKbd) pkgTagColor.copy(.1f) else Color.Transparent, CORNER_SM)
                                                    .border(1.dp, if (isIncluded || incKbd) pkgTagColor else tc.br, CORNER_SM)
                                                    .clickable {
                                                        actions.onAddPkgPrefix(value)
                                                        selectedIdx = -1
                                                        selectedAction = 0
                                                        runCatching { fr.requestFocus() } // continues this field.
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) { AppText("+", color = if (isIncluded || incKbd) pkgTagColor else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                            val exKbd = isRowSelected && selectedAction == 1
                                            Box(
                                                Modifier.size(20.dp)
                                                    .background(if (isExcluded) DANGER_RED.copy(.2f) else if (exKbd) DANGER_RED.copy(.1f) else Color.Transparent, CORNER_SM)
                                                    .border(1.dp, if (isExcluded || exKbd) DANGER_RED else tc.br, CORNER_SM)
                                                    .clickable {
                                                        actions.onAddExcludePkgPrefix(value)
                                                        selectedIdx = -1
                                                        selectedAction = 0
                                                        runCatching { fr.requestFocus() } // continues this field.
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) { AppText("−", color = if (isExcluded || exKbd) DANGER_RED else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                        }
                                    } else {
                                        val (label, packageLabel) = displayTagForPrefix(value, filter.pkgPrefixes)
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                                                Box(
                                                    Modifier.size(5.dp).background(
                                                        when {
                                                            isIncluded -> tc.ac
                                                            isExcluded -> DANGER_RED
                                                            else -> tc.td
                                                        },
                                                        RoundedCornerShape(50),
                                                    ),
                                                )
                                            }
                                            Column(Modifier.weight(1f)) {
                                                FullTextHint(value, modifier = Modifier.fillMaxWidth(), forceShow = isRowSelected) { onTextLayout ->
                                                    AppText(
                                                        label,
                                                        color = when {
                                                            isIncluded -> tc.tx
                                                            isExcluded -> DANGER_RED.copy(.8f)
                                                            else -> tc.ts
                                                        },
                                                        fontSize = 11.sp, fontFamily = MONO,
                                                        modifier = Modifier.fillMaxWidth(), overflow = TextOverflow.Ellipsis, maxLines = 1,
                                                        onTextLayout = onTextLayout,
                                                    )
                                                }
                                                if (packageLabel != null) {
                                                    AppText(
                                                        packageLabel, color = tc.td, fontSize = 9.sp,
                                                        fontFamily = MONO, overflow = TextOverflow.Ellipsis, maxLines = 1,
                                                    )
                                                }
                                            }
                                            AppText(
                                                (tab.analysis.tagCounts[value] ?: 0).toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO,
                                                modifier = Modifier.width(26.dp), overflow = TextOverflow.Clip,
                                            )
                                            val incKbd = isRowSelected && selectedAction == 0
                                            Box(
                                                Modifier.size(20.dp)
                                                    .background(if (isIncluded) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, CORNER_SM)
                                                    .border(1.dp, if (isIncluded || incKbd) tc.ac else tc.br, CORNER_SM)
                                                    .clickable {
                                                        actions.onToggleTag(value)
                                                        runCatching { fr.requestFocus() } // continues this field — see the file header's focus rule.
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) { AppText("+", color = if (isIncluded || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                            val exKbd = isRowSelected && selectedAction == 1
                                            Box(
                                                Modifier.size(20.dp)
                                                    .background(if (isExcluded) DANGER_RED.copy(.2f) else if (exKbd) DANGER_RED.copy(.1f) else Color.Transparent, CORNER_SM)
                                                    .border(1.dp, if (isExcluded || exKbd) DANGER_RED else tc.br, CORNER_SM)
                                                    .clickable {
                                                        actions.onToggleExcludeTag(value)
                                                        runCatching { fr.requestFocus() } // continues this field.
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) { AppText("−", color = if (isExcluded || exKbd) DANGER_RED else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRuleField(
    tab: LogTab,
    filter: Filter,
    model: FilterBarModel,
    actions: FilterBarActions,
    logFocusRequester: FocusRequester?,
    fr: FocusRequester,
    popup: MutableState<FilterFieldPopup>,
) {
    val tc = tc()
    fun refocusLog() { runCatching { logFocusRequester?.requestFocus() } }

    var input by remember(tab.id) { mutableStateOf(filter.kwInTag) }
    // Same single-writer sentinel shape as FilterPanel.kt's msgRuleInput/msgRuleLastSent
    // (FilterPanel.kt:745-762) — safe here only because of this bar's mutual-exclusion invariant
    // (see the file header): while this composable is mounted, FilterPanel.kt is not, so there is
    // only ever one writer racing this debounce.
    var lastSent by remember(tab.id) { mutableStateOf(filter.kwInTag) }
    var search by remember(tab.id) { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }
    var candidatesHovered by remember { mutableStateOf(false) }
    var popupState by popup
    // Bug fix (self-referential LaunchedEffect key — see FilterFieldPopup's own doc): a plain
    // boolean, keyed on real external inputs only, exactly like FilterPanel.kt's own
    // `showMsgRuleCandidates` (FilterPanel.kt:735/763-767).
    var showCandidates by remember { mutableStateOf(false) }
    var selectedIdx by remember { mutableStateOf(-1) }
    var selectedAction by remember { mutableStateOf(0) } // 0 = include, 1 = exclude
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    // Bug fix (popups covered the field) — see TagAndPkgField's own fieldHeightPx doc; shared by
    // both popups anchored to this field (the candidates popup and the scope chooser below).
    var fieldHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // ── Message-rule scope chooser — ports FilterPanel.kt:739-1010's pendingMessageRule/
    // msgRuleScopeOpen state machine verbatim (values/semantics; presentation is a Popup here
    // instead of an inline block — see this field's Popup below). Rendered/keyboard-driven
    // separately from the candidates popup above, exactly as the panel keeps `msgRuleScopeOpen`
    // independent of `showMsgRuleCandidates` rather than folding it into the same enum/state.
    var pendingMessageRule by remember(tab.id) { mutableStateOf<PendingMessageRuleDraft?>(null) }
    var msgRuleScopeOpen by remember(tab.id) { mutableStateOf(false) }
    var msgRuleScopeSearch by remember(tab.id) { mutableStateOf("") }
    var msgRuleScopeSelectedIdx by remember(tab.id) { mutableStateOf(0) }
    val msgRuleScopeFr = remember(tab.id) { FocusRequester() }

    LaunchedEffect(input) {
        val snap = input
        delay(if (tab.largeFileMode) 350 else 120)
        if (snap == input) {
            search = input
            lastSent = input
            actions.onSetKwInTag(input)
        }
    }
    LaunchedEffect(filter.kwInTag) {
        if (filter.kwInTag != lastSent) input = filter.kwInTag
    }
    // Exact match of FilterPanel.kt:763-767's own effect — keyed ONLY on (fieldFocused,
    // candidatesHovered); PILLS mutual exclusion is enforced at the render site, same as
    // TagAndPkgField above — see FilterFieldPopup's own doc.
    LaunchedEffect(fieldFocused, candidatesHovered) {
        if (fieldFocused || candidatesHovered) {
            showCandidates = true
        } else {
            delay(100)
            if (!fieldFocused && !candidatesHovered) showCandidates = false
        }
    }
    // Matches FilterPanel.kt:1008-1010 exactly: opening the scope chooser moves keyboard focus
    // into its own search field.
    LaunchedEffect(msgRuleScopeOpen) {
        if (msgRuleScopeOpen) runCatching { msgRuleScopeFr.requestFocus() }
    }

    val candidates = rememberBarUnifiedCandidates(tab, filter, search)

    // computeRelevantScopeTagsSync is `internal` in FilterPanel.kt; its own composable wrapper
    // (rememberRelevantScopeTags) is `private` to that file, so this is a bar-local copy of the
    // same "stale but usable" wrapper shape, calling the shared sync function directly — the same
    // reuse seam rememberBarUnifiedCandidates above already uses for computeUnifiedCandidatesSync.
    val relevantScopeTags = rememberBarRelevantScopeTags(tab, pendingMessageRule)
    val scopeOptionTags = remember(model.sortedTags, relevantScopeTags) {
        if (relevantScopeTags.isNullOrEmpty()) model.sortedTags else model.sortedTags.filter { it in relevantScopeTags }
    }
    val msgRuleScopeOptions = remember(scopeOptionTags, msgRuleScopeSearch) {
        messageRuleScopeOptions(scopeOptionTags, msgRuleScopeSearch)
    }
    val searchedMsgRuleScopeOptions = remember(msgRuleScopeOptions) { msgRuleScopeOptions.drop(1) }

    // Matches FilterPanel.kt's cancelPendingMessageRule (FilterPanel.kt:960-974) exactly: a single-
    // stage full clear — cancels any pending scope draft AND clears the committed filter value —
    // no "close the candidates popup first, clear on a second Escape" staging.
    fun cancelPendingMessageRule() {
        pendingMessageRule = null
        msgRuleScopeOpen = false
        msgRuleScopeSearch = ""
        msgRuleScopeSelectedIdx = 0
        input = ""
        search = ""
        selectedIdx = -1
        selectedAction = 0
        actions.onSetKwInTag("")
        runCatching { fr.requestFocus() }
    }

    // Matches FilterPanel.kt's openMessageRuleScopeChooser (FilterPanel.kt:952-958) exactly.
    fun openMessageRuleScopeChooser(include: Boolean, pattern: String, regex: Boolean, target: RuleTarget) {
        pendingMessageRule = PendingMessageRuleDraft(include, pattern, regex, target)
        msgRuleScopeOpen = true
        msgRuleScopeSearch = ""
        msgRuleScopeSelectedIdx = 0
        showCandidates = false
    }

    // Matches FilterPanel.kt's commitPendingMessageRule (FilterPanel.kt:976-988) exactly.
    fun commitPendingMessageRule(scope: MessageRuleScopeOption) {
        val pending = pendingMessageRule ?: return
        actions.onAddMessageRule(pending.include, pending.pattern, pending.regex, scope.tag, scope.packagePrefix, pending.target)
        pendingMessageRule = null
        msgRuleScopeOpen = false
        msgRuleScopeSearch = ""
        msgRuleScopeSelectedIdx = 0
        selectedIdx = -1
        selectedAction = 0
        runCatching { fr.requestFocus() }
    }

    // Matches FilterPanel.kt's commitContextualMessageRule (FilterPanel.kt:990-995) exactly — a
    // contextual candidate (tag/message-boundary match) commits directly, scoped to its own tag,
    // with no scope-chooser detour.
    fun commitContextualMessageRule(include: Boolean, candidate: MsgCandidate) {
        actions.onAddMessageRule(include, candidate.pattern, false, candidate.tag, null, candidate.target)
        selectedIdx = -1
        selectedAction = 0
        runCatching { fr.requestFocus() }
    }

    // Matches FilterPanel.kt's addMessageRuleCandidate (FilterPanel.kt:997-1000) exactly — the
    // dispatch point Bug 2 was missing: only `addsImmediately` candidates skip the scope chooser.
    fun addMessageRuleCandidate(include: Boolean, candidate: MsgCandidate) {
        if (candidate.addsImmediately) commitContextualMessageRule(include, candidate)
        else openMessageRuleScopeChooser(include, candidate.pattern, regex = false, candidate.target)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Fix 2 (layout): wraps only the field itself, same as TagAndPkgField above — measures
            // both width and height in the same callback so both popups anchored to this field
            // (the candidates popup and the scope chooser below) can position themselves directly
            // below it via TopStart + IntOffset(0, fieldHeightPx) instead of BottomStart, which
            // pins within the anchor's own bounds and grows upward, covering the field.
            Box(
                Modifier.weight(1f)
                    .onGloballyPositioned { coords ->
                        fieldWidthDp = with(density) { coords.size.width.toDp() }
                        fieldHeightPx = coords.size.height
                    },
            ) {
                InlineField(
                    input,
                    { input = it; selectedIdx = -1 },
                    if (filter.kwInTagRegex) "/pattern/…" else "search in messages…",
                    Modifier.fillMaxWidth()
                        .focusRequester(fr)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // Exact match of FilterPanel.kt:1437-1438's own gate: DirectionLeft/
                            // Right only get consumed when there's a selected candidate to act on
                            // (otherwise they're normal cursor movement in the text field).
                            val hasActionCandidate = candidates.getOrNull(selectedIdx) != null
                            if (!messageRuleInputConsumesKey(ev.key, hasActionCandidate)) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionDown -> { selectedIdx = (selectedIdx + 1).coerceAtMost(candidates.lastIndex); true }
                                Key.DirectionUp -> { selectedIdx = (selectedIdx - 1).coerceAtLeast(-1); true }
                                Key.DirectionLeft -> { selectedAction = 0; true }
                                Key.DirectionRight -> { selectedAction = 1; true }
                                Key.Escape -> {
                                    // PILLS has no panel equivalent (see TagAndPkgField's own
                                    // Escape doc) — closing it first is a bar-only affordance. With
                                    // PILLS not open this is a single-stage full clear, matching
                                    // FilterPanel.kt's cancelPendingMessageRule exactly (including
                                    // cancelling any pending scope-chooser draft).
                                    if (popupState == FilterFieldPopup.PILLS) {
                                        popupState = FilterFieldPopup.NONE
                                    } else {
                                        cancelPendingMessageRule()
                                    }
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    val c = candidates.getOrNull(selectedIdx)
                                    if (c != null) {
                                        addMessageRuleCandidate(selectedAction == 0, c)
                                    } else if (input.isNotBlank()) {
                                        // Bug 2 fix: typed (non-candidate) input must open the scope
                                        // chooser — matching FilterPanel.kt:1450-1454 — not commit
                                        // an unscoped "all tags" rule directly.
                                        val spec = messageRuleInputSpec(input, regexMode = filter.kwInTagRegex)
                                        openMessageRuleScopeChooser(selectedAction == 0, spec.pattern, spec.regex, spec.target)
                                    } else {
                                        return@onPreviewKeyEvent false
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    onClear = { cancelPendingMessageRule() },
                    flat = true, // Fix 1: this bar's own row carries the chrome, matching SearchBar.kt.
                )
                // !msgRuleScopeOpen matches FilterPanel.kt:1596's own gate — the scope chooser and
                // the candidates popup are mutually exclusive, same as the panel's inline block vs
                // showMsgRuleCandidates. fieldHeightPx > 0 guards the first-frame case — see
                // TagAndPkgField's own doc on the same guard.
                if (!msgRuleScopeOpen && showCandidates && popupState != FilterFieldPopup.PILLS && candidates.isNotEmpty() && fieldHeightPx > 0) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, fieldHeightPx),
                        properties = PopupProperties(focusable = false),
                        onDismissRequest = { showCandidates = false },
                    ) {
                        DisableSelection {
                            Box(
                                Modifier
                                    .widthIn(min = 200.dp)
                                    .width(fieldWidthDp)
                                    .background(tc.p, CORNER_SM)
                                    .border(1.dp, tc.br, CORNER_SM),
                            ) {
                                ScrollableItems(
                                    candidates.size,
                                    maxDp = 220,
                                    scrollToIndex = selectedIdx,
                                    modifier = Modifier
                                        .onPointerEvent(PointerEventType.Enter) { candidatesHovered = true }
                                        .onPointerEvent(PointerEventType.Exit) { candidatesHovered = false },
                                ) {
                                    candidates.forEachIndexed { idx, candidate ->
                                        val isRowSelected = idx == selectedIdx
                                        val isPid = candidate.target == RuleTarget.PID_TID
                                        val isIncluded = filter.messageRules.any { rule ->
                                            rule.include && rule.mode == filter.mode &&
                                                if (isPid) {
                                                    rule.target == RuleTarget.PID_TID && rule.pattern == candidate.pattern
                                                } else {
                                                    rule.target == RuleTarget.MESSAGE && rule.pattern == candidate.pattern && !rule.regex &&
                                                        (!candidate.addsImmediately || rule.tag == candidate.tag)
                                                }
                                        }
                                        val isExcluded = filter.messageRules.any { rule ->
                                            !rule.include && rule.mode == filter.mode &&
                                                if (isPid) {
                                                    rule.target == RuleTarget.PID_TID && rule.pattern == candidate.pattern
                                                } else {
                                                    rule.target == RuleTarget.MESSAGE && rule.pattern == candidate.pattern && !rule.regex &&
                                                        (!candidate.addsImmediately || rule.tag == candidate.tag)
                                                }
                                        }
                                        // Fix 3 (panel parity, FilterPanel.kt:1624-1683): the row
                                        // itself does NOT commit on click — only the +/- boxes do.
                                        HoverBox(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                            baseBg = if (isRowSelected) tc.abg else Color.Transparent,
                                            hoverBg = tc.hv,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Box(
                                                    Modifier.size(5.dp).background(
                                                        when {
                                                            isIncluded -> tc.ac
                                                            isExcluded -> DANGER_RED
                                                            else -> tc.td
                                                        },
                                                        RoundedCornerShape(50),
                                                    ),
                                                )
                                                if (isPid) {
                                                    AppText(
                                                        "pid", color = tc.td.copy(.7f), fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(end = 2.dp),
                                                    )
                                                }
                                                if (!candidate.inScope) {
                                                    AppText(
                                                        "other", color = tc.td.copy(.7f), fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(end = 2.dp),
                                                    )
                                                }
                                                FullTextHint(candidate.label, modifier = Modifier.weight(1f), forceShow = isRowSelected) { onTextLayout ->
                                                    AppText(
                                                        candidate.label,
                                                        color = when {
                                                            isIncluded -> tc.tx
                                                            isExcluded -> DANGER_RED.copy(.8f)
                                                            !candidate.inScope -> tc.td
                                                            else -> tc.ts
                                                        },
                                                        fontSize = 11.sp, fontFamily = MONO,
                                                        modifier = Modifier.fillMaxWidth(), overflow = TextOverflow.Ellipsis, maxLines = 1,
                                                        onTextLayout = onTextLayout,
                                                    )
                                                }
                                                val incKbd = isRowSelected && selectedAction == 0
                                                Box(
                                                    Modifier.size(20.dp)
                                                        .background(if (isIncluded) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, CORNER_SM)
                                                        .border(1.dp, if (isIncluded || incKbd) tc.ac else tc.br, CORNER_SM)
                                                        .clickable { addMessageRuleCandidate(true, candidate) },
                                                    contentAlignment = Alignment.Center,
                                                ) { AppText("+", color = if (isIncluded || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                                val exKbd = isRowSelected && selectedAction == 1
                                                Box(
                                                    Modifier.size(20.dp)
                                                        .background(if (isExcluded) DANGER_RED.copy(.2f) else if (exKbd) DANGER_RED.copy(.1f) else Color.Transparent, CORNER_SM)
                                                        .border(1.dp, if (isExcluded || exKbd) DANGER_RED else tc.br, CORNER_SM)
                                                        .clickable { addMessageRuleCandidate(false, candidate) },
                                                    contentAlignment = Alignment.Center,
                                                ) { AppText("−", color = if (isExcluded || exKbd) DANGER_RED else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // ── Message-rule scope chooser (Bug 2) ──────────────────────────────────────────
                // Ports FilterPanel.kt:1467-1595's inline scope-chooser block — same prompt/pattern
                // label/"All" row/search field/option list, same commit semantics — but rendered as
                // a Popup anchored to this field instead of an inline block, so opening it doesn't
                // change the bar's own height (see the file header's "Popups, not inline dropdowns"
                // section). `focusable = true` (unlike the read-only candidate/pills popups above)
                // because this popup hosts its own editable InlineField that must receive real
                // keyboard focus and input — same precedent as e.g. AnnotationPanel.kt's
                // CreateDiagramPopup/FilterPanel.kt:3105's SavedFilterOptionsMenu.
                // fieldHeightPx > 0 guards the first-frame case — see TagAndPkgField's own doc.
                if (msgRuleScopeOpen && fieldHeightPx > 0) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, fieldHeightPx),
                        properties = PopupProperties(focusable = true),
                        onDismissRequest = { cancelPendingMessageRule() },
                    ) {
                        DisableSelection {
                            Column(
                                Modifier
                                    .widthIn(min = 240.dp)
                                    .width(fieldWidthDp)
                                    .background(tc.p, FILTER_BAR_POPUP_SHAPE)
                                    .border(1.dp, tc.br, FILTER_BAR_POPUP_SHAPE)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppText(
                                        messageRuleScopePrompt(pendingMessageRule?.include ?: true),
                                        color = if (pendingMessageRule?.include == false) DANGER_RED else tc.ac,
                                        fontSize = 10.sp,
                                        fontFamily = UI,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    SquareIconButton("×", fontSize = 12.sp, onClick = { cancelPendingMessageRule() })
                                }
                                pendingMessageRule?.let { pending ->
                                    FullTextHint(pendingMessageRulePatternLabel(pending)) { onTextLayout ->
                                        AppText(
                                            pendingMessageRulePatternLabel(pending),
                                            color = tc.tx,
                                            fontSize = 11.sp,
                                            fontFamily = MONO,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth(),
                                            onTextLayout = onTextLayout,
                                        )
                                    }
                                }
                                HoverBox(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                    baseBg = if (msgRuleScopeSelectedIdx == 0) tc.abg else Color.Transparent,
                                    hoverBg = tc.hv,
                                    onClick = { commitPendingMessageRule(messageRuleAllScope()) },
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppText(
                                            "All",
                                            color = if (msgRuleScopeSelectedIdx == 0) tc.tx else tc.ts,
                                            fontSize = 11.sp,
                                            fontFamily = MONO,
                                        )
                                    }
                                }
                                InlineField(
                                    msgRuleScopeSearch,
                                    { msgRuleScopeSearch = it; msgRuleScopeSelectedIdx = 0 },
                                    "scope tag or prefix…",
                                    Modifier.fillMaxWidth()
                                        .focusRequester(msgRuleScopeFr)
                                        .onPreviewKeyEvent { ev ->
                                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (ev.key) {
                                                Key.DirectionDown -> {
                                                    msgRuleScopeSelectedIdx =
                                                        (msgRuleScopeSelectedIdx + 1).coerceAtMost(msgRuleScopeOptions.lastIndex)
                                                    true
                                                }
                                                Key.DirectionUp -> {
                                                    msgRuleScopeSelectedIdx = (msgRuleScopeSelectedIdx - 1).coerceAtLeast(0)
                                                    true
                                                }
                                                Key.Enter, Key.NumPadEnter -> {
                                                    msgRuleScopeOptions.getOrNull(msgRuleScopeSelectedIdx)?.let { commitPendingMessageRule(it) }
                                                    true
                                                }
                                                Key.Escape -> { cancelPendingMessageRule(); true }
                                                else -> false
                                            }
                                        },
                                    onClear = { msgRuleScopeSearch = ""; msgRuleScopeSelectedIdx = 0 },
                                )
                                ScrollableItems(
                                    searchedMsgRuleScopeOptions.size,
                                    maxDp = 140,
                                    scrollToIndex = msgRuleScopeSelectedIdx - 1,
                                ) {
                                    searchedMsgRuleScopeOptions.forEachIndexed { idx, scope ->
                                        val optionIndex = idx + 1
                                        HoverBox(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                            baseBg = if (msgRuleScopeSelectedIdx == optionIndex) tc.abg else Color.Transparent,
                                            hoverBg = tc.hv,
                                            onClick = { commitPendingMessageRule(scope) },
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                AppText(
                                                    when {
                                                        scope.isAll -> "all"
                                                        scope.packagePrefix != null -> "pkg"
                                                        else -> "tag"
                                                    },
                                                    color = tc.td,
                                                    fontSize = 9.sp,
                                                    fontFamily = UI,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.width(26.dp),
                                                )
                                                FullTextHint(
                                                    scope.label,
                                                    modifier = Modifier.weight(1f),
                                                    forceShow = msgRuleScopeSelectedIdx == optionIndex,
                                                ) { onTextLayout ->
                                                    AppText(
                                                        scope.label,
                                                        color = if (msgRuleScopeSelectedIdx == optionIndex) tc.tx else tc.ts,
                                                        fontSize = 11.sp,
                                                        fontFamily = MONO,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        overflow = TextOverflow.Ellipsis,
                                                        maxLines = 1,
                                                        onTextLayout = onTextLayout,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PillBtn(".*", active = filter.kwInTagRegex, onClick = { actions.onToggleKwInTagRegex(); refocusLog() })
        }
    }
}

// ── Regex mode ───────────────────────────────────────────────────────────────────────────────────
@Composable
private fun RegexModeBarContent(
    tab: LogTab,
    filter: Filter,
    model: FilterBarModel,
    actions: FilterBarActions,
    logFocusRequester: FocusRequester?,
) {
    val tc = tc()
    fun refocusLog() { runCatching { logFocusRequester?.requestFocus() } }
    val fr = remember(tab.id) { FocusRequester() }

    // Same single-writer debounce sentinel shape as FilterPanel.kt's kwDisplay/kwLastSent
    // (FilterPanel.kt:719-727) — safe here for the same mutual-exclusion reason as
    // MessageRuleField's own sentinel above.
    var display by remember(tab.id) { mutableStateOf(filter.kwText) }
    var lastSent by remember(tab.id) { mutableStateOf(filter.kwText) }
    var fieldFocused by remember { mutableStateOf(false) }
    var historyHovered by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var selectedIdx by remember { mutableStateOf(-1) }
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    // Bug fix (popup covered the field) — see TagAndPkgField's own fieldHeightPx doc above.
    var fieldHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(display) {
        val snap = display
        delay(if (tab.largeFileMode) 350 else 150)
        if (snap == display) { lastSent = display; actions.onSetKw(display) }
    }
    LaunchedEffect(filter.kwText) { if (filter.kwText != lastSent) display = filter.kwText }
    LaunchedEffect(fieldFocused, historyHovered) {
        if (fieldFocused || historyHovered) {
            showHistory = true
        } else {
            delay(100)
            if (!fieldFocused && !historyHovered) showHistory = false
        }
    }

    val matchingHistory = remember(model.regexHistory, display) {
        if (display.isBlank()) model.regexHistory else model.regexHistory.filter { it.contains(display, ignoreCase = true) }
    }
    val invalid = display.isNotBlank() && !isValidRegexPattern(display)

    // Commit contract (file header / AppState.rememberRegexPattern's own doc): Enter or an
    // explicit history pick applies onSetKw SYNCHRONOUSLY — otherwise, inside the debounce window,
    // Enter would look like a no-op — and records history only for a non-blank, syntactically
    // valid pattern.
    fun commit(pattern: String) {
        display = pattern
        lastSent = pattern
        actions.onSetKw(pattern)
        actions.onRememberRegexPattern(pattern)
        showHistory = false
        selectedIdx = -1
    }

    Column(Modifier.fillMaxWidth()) {
        FilterBarInputRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Same switcher as TagsModeBarContent's own field row (first element, smaller sizing) — both
            // modes must show it, since it is the only way to change filter.mode while the panel is
            // hidden. See TagsModeBarContent for the shared `onToggle` dispatch this mirrors.
            SegmentedControl(
                options = listOf("Tags", "Regex"),
                selectedIndices = if (filter.mode == FilterMode.KEYWORD) setOf(1) else setOf(0),
                onToggle = { index ->
                    if (index == 1) actions.onStartRegexSearch() else actions.onSetFilterMode(FilterMode.TAGS)
                    refocusLog() // "ends an interaction" — see the file header's focus rule.
                },
                segmentHeight = 20.dp,
                segmentFontSize = 10.sp,
                segmentHorizontalPadding = 6.dp,
            )
            Box(
                Modifier.weight(1f)
                    .onGloballyPositioned { coords ->
                        fieldWidthDp = with(density) { coords.size.width.toDp() }
                        fieldHeightPx = coords.size.height
                    },
            ) {
                InlineField(
                    display,
                    { display = it; selectedIdx = -1 },
                    "visible log row regex…",
                    Modifier.fillMaxWidth()
                        .focusRequester(fr)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .border(if (invalid) 1.dp else 0.dp, if (invalid) DANGER_RED else Color.Transparent, CORNER_SM)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionDown -> { selectedIdx = (selectedIdx + 1).coerceAtMost(matchingHistory.lastIndex); true }
                                Key.DirectionUp -> { selectedIdx = (selectedIdx - 1).coerceAtLeast(-1); true }
                                // The panel's own KEYWORD/regex field (FilterPanel.kt's kwDisplay,
                                // :1334-1341) has no Escape handling at all — no revert-to-committed-
                                // value stage and no "return to the log" stage. This used to add
                                // both; per explicit user direction, identical-to-panel now wins over
                                // that earlier design note. The only thing left is closing this bar's
                                // own history dropdown (no panel equivalent to be unfaithful to,
                                // same as PILLS elsewhere in this file) — with it closed, Escape is
                                // simply not consumed here, same as the panel.
                                Key.Escape -> {
                                    if (showHistory && matchingHistory.isNotEmpty()) {
                                        showHistory = false
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    val picked = matchingHistory.getOrNull(selectedIdx)
                                    if (picked != null) {
                                        commit(picked)
                                    } else if (display.isNotBlank()) {
                                        commit(display)
                                    } else {
                                        return@onPreviewKeyEvent false
                                    }
                                    runCatching { fr.requestFocus() } // Enter continues — stays in the field.
                                    true
                                }
                                else -> false
                            }
                        },
                    onClear = { display = ""; runCatching { fr.requestFocus() } },
                    flat = true, // Fix 1: this bar's own row carries the chrome, matching SearchBar.kt.
                )
                // fieldHeightPx > 0 guards the first-frame case — see TagAndPkgField's own doc.
                if (showHistory && matchingHistory.isNotEmpty() && fieldHeightPx > 0) {
                    // Fix 2 (layout): TopStart + the field's own measured height as the offset,
                    // same treatment as the tag/message candidate popups above — BottomStart pinned
                    // within the anchor's own bounds and grew upward, covering the field; no more
                    // hardcoded 34dp offset or a 360dp width unrelated to the field it belongs to.
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, fieldHeightPx),
                        properties = PopupProperties(focusable = false),
                        onDismissRequest = { showHistory = false },
                    ) {
                        DisableSelection {
                            Column(
                                Modifier
                                    .widthIn(min = 240.dp)
                                    .width(fieldWidthDp)
                                    .background(tc.p, CORNER_SM)
                                    .border(1.dp, tc.br, CORNER_SM)
                                    .onPointerEvent(PointerEventType.Enter) { historyHovered = true }
                                    .onPointerEvent(PointerEventType.Exit) { historyHovered = false },
                            ) {
                                BoundedScrollBoxDp(maxHeightDp = 200) {
                                    matchingHistory.forEachIndexed { idx, pattern ->
                                        val isRowSelected = idx == selectedIdx
                                        HoverBox(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                            baseBg = if (isRowSelected) tc.abg else Color.Transparent,
                                            hoverBg = tc.hv,
                                            onClick = { commit(pattern); runCatching { fr.requestFocus() } },
                                        ) {
                                            AppText(
                                                pattern, color = tc.tx, fontSize = 11.sp, fontFamily = MONO,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                                // Stored patterns can carry confidential log fragments (a customer's
                                // package name, an internal hostname) — this is the only way to clear
                                // them; treated as "continues" (stays in the field) rather than "ends"
                                // since it's a housekeeping action on the field's own dropdown, not a
                                // navigation away from it.
                                HoverBox(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                    hoverBg = tc.hv,
                                    onClick = { actions.onClearRegexHistory(); runCatching { fr.requestFocus() } },
                                ) {
                                    AppText(
                                        "Clear history", color = DANGER_RED, fontSize = 10.sp, fontFamily = UI,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Trailing end of the row — see FilterBarResidualChip's doc for why this moved off its
            // own dedicated row (Regex mode has no per-field badge to trail, unlike Tags mode's
            // message field, so this is simply the last element here).
            FilterBarResidualChip(filter, actions, logFocusRequester)
        }
    }
}
