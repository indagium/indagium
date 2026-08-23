@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.diagram3.Seq3AddResult
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3DelaySuggestion
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.nudgeSeq3OrderPin
import com.indagium.diagram3.parseSeq3Timestamp
import com.indagium.diagram3.seq3DisplayName
import com.indagium.diagram3.seq3FilterCounts
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3Select
import com.indagium.diagram3.seq3SuggestedDelays
import com.indagium.model.LogEntry
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

private const val ADD_HINT_DURATION_MS = 2_500L
private const val ADD_ROW_RANGE_LIMIT = 2_000
private const val SEQ3_QUEUE_DOUBLE_CLICK_WINDOW_MS = 350L
private const val SEQ3_LIFELINES_MIN_HEIGHT_DP = 120f
private const val SEQ3_LIFELINES_MAX_HEIGHT_DP = 420f
private const val SEQ3_ARTIFACTS_MIN_HEIGHT_DP = 100f
private const val SEQ3_ARTIFACTS_MAX_HEIGHT_DP = 360f
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private val SEQ3_ACTION_BADGE_SIZE = 24.dp
private val SEQ3_SUBMESSAGE_ROW_HEIGHT = 44.dp

/** Diagram-wide default segments dropdown (SectionHeader's "names ▾") — every value is a concrete
 *  `Int`, since [com.indagium.diagram3.Seq3Document.lifelineDisplaySegments] has no "inherit"
 *  state of its own to offer. */
internal val SEQ3_DOCUMENT_DISPLAY_SEGMENT_OPTIONS: List<Pair<String, Int>> = listOf(
    "Full name" to 0,
    "Last segment" to 1,
    "Last 2" to 2,
    "Last 3" to 3,
)

/** Per-lifeline segments dropdown ("name ▾") — a superset of
 *  [SEQ3_DOCUMENT_DISPLAY_SEGMENT_OPTIONS] with the "inherit the diagram default" (`null`) choice
 *  prepended, matching [com.indagium.diagram3.Seq3Lifeline.displaySegments]'s own null-means-inherit
 *  contract. */
internal val SEQ3_LIFELINE_DISPLAY_SEGMENT_OPTIONS: List<Pair<String, Int?>> = listOf("Diagram default" to null) +
    SEQ3_DOCUMENT_DISPLAY_SEGMENT_OPTIONS.map { (label, value) -> label to (value as Int?) }

/** Label shown on a lifeline row's "name ▾" control — the inverse of picking an entry from
 *  [SEQ3_LIFELINE_DISPLAY_SEGMENT_OPTIONS]. Falls back to "Last N" for any [segments] value a menu
 *  entry doesn't cover (never thrown on a decoded note holding an unexpected number). */
internal fun seq3LifelineDisplaySegmentsLabel(segments: Int?): String =
    SEQ3_LIFELINE_DISPLAY_SEGMENT_OPTIONS.firstOrNull { it.second == segments }?.first ?: "Last ${segments ?: 0}"

/** Label shown on the Lifelines section header's diagram-wide "names ▾" control — the
 *  [SEQ3_DOCUMENT_DISPLAY_SEGMENT_OPTIONS] counterpart of [seq3LifelineDisplaySegmentsLabel]. */
internal fun seq3DocumentDisplaySegmentsLabel(segments: Int): String =
    SEQ3_DOCUMENT_DISPLAY_SEGMENT_OPTIONS.firstOrNull { it.second == segments }?.first ?: "Last $segments"

/** Applies a horizontal splitter drag to the Fragments & notes section — the
 *  [seq3ClampLifelinesHeight] counterpart for WP3 item 9's promoted section. Same inversion
 *  rationale as that function's own doc. Used on its own only when the pane above the divider is
 *  the weighted one (Lifelines hidden), which is the arrangement where it already behaves like
 *  the Lifelines divider; otherwise the drag goes through [seq3DragArtifactsBoundary]. */
internal fun seq3ClampArtifactsHeight(
    current: Float,
    delta: Float,
    min: Float = SEQ3_ARTIFACTS_MIN_HEIGHT_DP,
    max: Float = SEQ3_ARTIFACTS_MAX_HEIGHT_DP,
): Float = (current - delta).coerceIn(min, max)

/**
 * Drags the Lifelines/Artifacts boundary the way the Messages/Lifelines one already drags: the
 * pane immediately ABOVE the divider grows and the pane below it shrinks, by the same amount.
 *
 * The two dividers only *look* like they need different code. Messages holds `weight(1f)`, so
 * dragging the Lifelines divider gets this for free — the pane above the boundary is the flexible
 * one, and it absorbs the change on the spot. The Artifacts divider has a FIXED pane above it
 * (Lifelines) and the flexible pane two positions away, so resizing Artifacts alone pulled the
 * height out of Messages and slid the entire Lifelines section up or down — the divider moved a
 * boundary it wasn't sitting on. Trading the two adjacent heights instead keeps their sum constant,
 * so Messages never notices and nothing above Lifelines moves.
 *
 * Both clamps are honoured jointly — the transfer is limited to the smaller of what Artifacts can
 * give ([seq3ClampArtifactsHeight]) and what Lifelines can take ([seq3ClampLifelinesHeight]), so
 * hitting either bound stops the boundary dead rather than letting one side keep moving.
 *
 * Returns `(lifelines, artifacts)`.
 */
internal fun seq3DragArtifactsBoundary(
    lifelines: Float,
    artifacts: Float,
    delta: Float,
): Pair<Float, Float> {
    // [VDivider] reports positive deltas downward, and dragging this divider down means a taller
    // Lifelines above it and a shorter Artifacts below — the same inversion the two clamp helpers
    // already encode for their own sections.
    val offered = artifacts - seq3ClampArtifactsHeight(artifacts, delta)
    val lifelinesAfter = seq3ClampLifelinesHeight(lifelines, -offered)
    val transferred = lifelinesAfter - lifelines
    return lifelinesAfter to (artifacts - transferred)
}

private val MESSAGE_KIND_OPTIONS = listOf(
    Seq3Kind.CALL,
    Seq3Kind.RETURN,
    Seq3Kind.ASYNC,
    Seq3Kind.SELF,
)

private enum class Seq3AddDialog {
    ROWS,
    CUSTOM,
}

private enum class Seq3CustomPositionMode {
    START,
    END,
    BEFORE,
    AFTER,
    INDEX,
}

/**
 * Applies a horizontal splitter drag to the lower Lifelines section.
 *
 * [VDivider] reports positive deltas when the pointer moves down, while this state stores the
 * lower section's height. Moving the divider down therefore reduces that height; moving it up
 * increases it. Keeping that inversion here makes the behavior match the other stacked panels.
 */
internal fun seq3ClampLifelinesHeight(
    current: Float,
    delta: Float,
    min: Float = SEQ3_LIFELINES_MIN_HEIGHT_DP,
    max: Float = SEQ3_LIFELINES_MAX_HEIGHT_DP,
): Float = (current - delta).coerceIn(min, max)

/** Which of the panel's three stacked sections [Seq3QueuePanel] gives `Modifier.weight(1f)`. */
internal enum class Seq3PanelSection { MESSAGES, LIFELINES, ARTIFACTS }

/**
 * WP8: generalises the panel's "last section standing absorbs the remaining height" rule from two
 * sections to three. Lifelines and Artifacts each normally hold either a fixed, user-resized
 * height (dragged via their own [VDivider]) or a small collapsed header height — they only grow to
 * fill the panel when nothing higher in priority is doing it, so the `Column` in [Seq3QueuePanel]
 * (`Modifier.fillMaxHeight()` from its caller) doesn't leave dead space below a shorter stack.
 *
 * Priority is Messages > Lifelines > Artifacts. Each argument means "this section is visible AND
 * expanded", so only a section that actually has a body to show can claim the remaining height.
 * An earlier revision let Lifelines take the weight whenever Messages was collapsed *regardless of
 * whether Lifelines itself was expanded* — deliberately sacrificing a section's own collapse
 * preference to avoid dead space. In practice that traded a small gap for a much worse one:
 * collapse all three and the panel stretched a *collapsed* Lifelines section over the entire
 * height, so its 48dp header floated at the top of a panel-sized void with Artifacts pinned to the
 * far bottom. A collapsed section has nothing to fill space with, so it no longer volunteers.
 *
 * At most one section is returned; `null` means no visible section is expanded, so the Column is
 * intentionally shorter than its container and the leftover simply reads as panel background —
 * three collapsed headers stacked at the top, which is what "collapse everything" should look
 * like.
 *
 * Visibility on its own is still enough to be "in the running" when the section IS expanded — an
 * empty-but-toggled-on Artifacts section still claims the weight, since it renders its header, its
 * "+ note" action, and an empty-state hint in place of the row list (see
 * `Seq3FragmentsAndNotesSection`).
 */
internal fun seq3PanelWeightedSection(
    messagesWeighted: Boolean,
    lifelinesWeighted: Boolean,
    artifactsWeighted: Boolean,
): Seq3PanelSection? = when {
    messagesWeighted -> Seq3PanelSection.MESSAGES
    lifelinesWeighted -> Seq3PanelSection.LIFELINES
    artifactsWeighted -> Seq3PanelSection.ARTIFACTS
    else -> null
}

/**
 * [seq3PanelWeightedSection] applied to a live [Seq3ViewState] — the one place the "visible AND
 * expanded" eligibility rule for each of the three sections is written down. Split out from
 * [Seq3QueuePanel] so a test can pin the combination that used to stretch a *collapsed* Lifelines
 * section over the whole panel (everything collapsed ⇒ nothing is weighted) without composing the
 * panel.
 */
internal fun seq3PanelWeightedSectionFor(view: Seq3ViewState): Seq3PanelSection? =
    seq3PanelWeightedSection(
        view.messagesSectionOpen && view.messagesExpanded,
        view.lifelinesSectionOpen && view.lifelinesExpanded,
        view.artifactsSectionOpen && view.artifactsExpanded,
    )

/** WP8 (revised): whether the Fragments & notes section renders in the panel at all — purely
 *  [Seq3ViewState.artifactsSectionOpen]. Split out (mirroring [seq3PanelWeightedSection]'s own
 *  "split out for testability" shape) so a test can assert this no longer depends on the document
 *  having any fragments/notes — the function doesn't even take a [Seq3Document] parameter, unlike
 *  the old inline `view.artifactsSectionOpen && (document.fragments.isNotEmpty() || ...)` this
 *  replaces. An empty-but-toggled-on section still renders (header, "+ note", an empty-state hint
 *  in place of the row list) — see `Seq3FragmentsAndNotesSection`. */
internal fun seq3ArtifactsSectionVisible(view: Seq3ViewState): Boolean = view.artifactsSectionOpen

// ── The panel is a queue — design spec §04 + §06 + §07 ─────────────────────────────────────────
//
// A thin composable shell around `diagram3.Seq3Queue` (filter/sort/selection math) and
// `diagram3.Seq3Commands` (every mutation) — exactly the split `diagram.ManualDiagramMessageQueue`
// had for the v1/v2 panel. EVERY editing verb below routes through
// `state.seq3Sessions.applyCommand(session.id, Seq3Command…)`; nothing here ever calls
// `session.document.copy(...)` directly, so ⌘Z stays uniform (see this phase's brief).

@Composable
internal fun Seq3QueuePanel(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, modifier: Modifier) {
    val tc = tc()
    val document = session.document
    val counts = seq3FilterCounts(document)
    val rows = remember(document, view.filter, view.textFilter, view.sort) {
        seq3QueueRows(document, view.filter, view.textFilter, view.sort)
    }
    val visibleIds = remember(rows) { rows.map(Seq3Message::id) }
    val listState = rememberLazyListState()

    // Two-way row<->arrow (spec §04): a canvas arrow click already reset the filter/text before
    // setting scrollRequestId (see Seq3Canvas), so by the time `rows` reflects that reset, the
    // target id is guaranteed visible and this only has to find and scroll to it.
    LaunchedEffect(view.scrollRequestId, rows) {
        val targetId = view.scrollRequestId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            view.scrollRequestId = null
        }
    }

    // WP8 (revised): whether the Artifacts section renders at all — purely the user's own
    // show/hide toggle. It used to also require the document to already have a fragment or note,
    // which meant turning the section ON for an empty document rendered NOTHING at all — no
    // header, no "+ note" button — hiding the only way to add the first one. An empty section now
    // still renders (header + empty-state hint); see `Seq3FragmentsAndNotesSection`.
    val artifactsVisible = seq3ArtifactsSectionVisible(view)
    // 1a header rework: Messages is now a section toggle like the other two, so a hidden Messages
    // section is neither drawn nor eligible for the weight(1f) chain — `messagesExpanded` alone
    // (which only collapses a *visible* section to its header) is no longer the whole story.
    val messagesVisible = view.messagesSectionOpen
    // A section is eligible for the weight(1f) remainder only when it is both shown and expanded —
    // a collapsed section is a fixed-height header with no body to stretch. See
    // [seq3PanelWeightedSection].
    val messagesWeighted = messagesVisible && view.messagesExpanded
    val weightedSection = seq3PanelWeightedSectionFor(view)

    Column(modifier.background(tc.p)) {
        if (messagesWeighted) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Seq3QueueHeader(state, session, counts, view)
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (counts.needsTarget > 0) {
                        Seq3NeedsTargetBanner(counts.needsTarget) {
                            // Spec §05: the banner is what starts the guided pass. `startSeq3GuidedPass`
                            // returns null only when nothing is unresolved — unreachable here, since this
                            // banner is itself gated on needsTarget > 0.
                            view.guidedPass = startSeq3GuidedPass(document)
                            runCatching { view.focusRequester.requestFocus() }
                        }
                    }
                    // WP11 auto-suggest: OFFER, never insert silently — see Seq3DelaySuggest.kt's own
                    // header for the "manual insert plus auto-suggest" decision this implements.
                    val delaySuggestions = remember(document, view.dismissedDelaySuggestionAfterIds) {
                        seq3SuggestedDelays(document).filterNot { it.afterMessageId in view.dismissedDelaySuggestionAfterIds }
                    }
                    delaySuggestions.firstOrNull()?.let { suggestion ->
                        Seq3DelaySuggestionBanner(
                            suggestion = suggestion,
                            moreCount = delaySuggestions.size - 1,
                            onInsert = { seq3InsertDelayAfter(state, session, suggestion.afterMessageId) },
                            onDismiss = { view.dismissedDelaySuggestionAfterIds = view.dismissedDelaySuggestionAfterIds + suggestion.afterMessageId },
                        )
                    }
                    Seq3FilterChipsRow(view, counts)
                    Seq3FilterTextAndSortRow(view)
                    Box(Modifier.weight(1f)) {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(end = 6.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(rows, key = Seq3Message::id) { message ->
                                Seq3QueueRow(state, session, view, document, message, visibleIds)
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                            style = appScrollbarStyle(tc),
                        )
                    }
                    Seq3QueueFooter(counts) { view.regenerateSheetOpen = true }
                }
            }
        } else if (messagesVisible) {
            Seq3QueueHeader(state, session, counts, view)
        }
        if (view.lifelinesSectionOpen) {
            if (messagesWeighted) {
                VDivider { delta ->
                    view.lifelinesSectionHeightDp =
                        seq3ClampLifelinesHeight(view.lifelinesSectionHeightDp, delta)
                }
            }
            val lifelinesModifier = when {
                weightedSection == Seq3PanelSection.LIFELINES -> Modifier.weight(1f).fillMaxWidth()
                view.lifelinesExpanded -> Modifier.height(view.lifelinesSectionHeightDp.dp).fillMaxWidth()
                // Collapsed: wrap the section header and nothing else, the same way a collapsed
                // Messages section does by simply rendering its header. A forced height here (this
                // was 48dp against a ~32dp header) padded 16dp of blank panel underneath, which
                // read as a gap between Lifelines and Artifacts that Messages/Lifelines never had.
                else -> Modifier.fillMaxWidth()
            }
            Seq3LifelinesSection(state, session, view, lifelinesModifier)
        }
        // Item 9 — promoted out of the Messages area (it used to render above the message
        // LazyColumn) into its own top-level, independently collapsible/resizable section, same
        // "own VDivider + own clamped height" shape as Lifelines above. WP8 3c added its own
        // show/hide toggle (`artifactsSectionOpen`); `artifactsVisible` is now exactly that toggle
        // (see its own comment above) — an empty document still shows the section when toggled on.
        if (artifactsVisible) {
            // The Lifelines divider above shows only when ITS pane above (Messages) is expanded.
            // This is that same rule applied to this divider's own pane above — Lifelines when it
            // is open, otherwise Messages. Collapse everything and neither divider draws, so the
            // three headers stack identically instead of Artifacts being the only separated one.
            // …and only when BOTH panes it separates can actually change size. A collapsed
            // section is just its header, so there is nothing to drag and the handle is not drawn
            // at all — rather than drawn but inert, or worse, drawn and quietly resizing something
            // else.
            val paneAboveExpanded =
                if (view.lifelinesSectionOpen) view.lifelinesExpanded else messagesWeighted
            if (paneAboveExpanded && view.artifactsExpanded) {
                // Lifelines is present and fixed-height: the two trade, and Messages — the
                // weighted section — is not touched by any code path here. The one exception is
                // Lifelines being hidden or itself weighted, where there is no fixed pane above to
                // trade with and no Lifelines that could move either way.
                val tradesWithLifelines = view.lifelinesSectionOpen &&
                    weightedSection != Seq3PanelSection.LIFELINES
                VDivider { delta ->
                    if (tradesWithLifelines) {
                        val (lifelines, artifacts) = seq3DragArtifactsBoundary(
                            view.lifelinesSectionHeightDp,
                            view.artifactsSectionHeightDp,
                            delta,
                        )
                        view.lifelinesSectionHeightDp = lifelines
                        view.artifactsSectionHeightDp = artifacts
                    } else {
                        view.artifactsSectionHeightDp =
                            seq3ClampArtifactsHeight(view.artifactsSectionHeightDp, delta)
                    }
                }
            }
            val artifactsModifier = when {
                weightedSection == Seq3PanelSection.ARTIFACTS -> Modifier.weight(1f).fillMaxWidth()
                view.artifactsExpanded -> Modifier.height(view.artifactsSectionHeightDp.dp).fillMaxWidth()
                // Collapsed: wrap the header, same as Lifelines above.
                else -> Modifier.fillMaxWidth()
            }
            Seq3FragmentsAndNotesSection(state, session, view, document, artifactsModifier)
        }
    }
}

@Composable
private fun Seq3LifelinesSection(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val document = session.document
    val lifelines = remember(document) { document.lifelines.sortedBy { it.ordinal } }
    val lifelineIds = remember(lifelines) { lifelines.map { it.id } }
    var addDialogOpen by remember(session.id) { mutableStateOf(false) }
    var editingId by remember(session.id) { mutableStateOf<String?>(null) }
    var editingText by remember(session.id) { mutableStateOf("") }
    var hint by remember(session.id) { mutableStateOf<String?>(null) }

    // Drag-to-reorder (item 4, Variant B) — same state quintet/idioms as AnnotationPanel's own
    // note-block drag (dragBlockId/dragOffsetY/justReleasedBlockId/liveVisualBlockIds +
    // cumulativeBlockOffsets/blockOrderDuringDrag for the variable-row-height math, since a row
    // with its merged-tag block expanded is taller than a plain row). Deliberately scoped to a
    // dedicated "⠿" handle (Seq3LifelineRow's own dragHandleModifier param) rather than the whole
    // row: a lifeline row now hosts two dropdowns and an inline rename field a whole-row gesture
    // would fight — see AnnotationPanel.kt:479-485's own rationale, which applies identically here.
    var dragId by remember(session.id) { mutableStateOf<String?>(null) }
    var dragOffsetY by remember(session.id) { mutableStateOf(0f) }
    var justReleasedId by remember(session.id) { mutableStateOf<String?>(null) }
    var liveVisualIds by remember(session.id) { mutableStateOf(emptyList<String>()) }
    val rowHeights = remember(session.id) { mutableStateMapOf<String, Float>() }
    val rowDensity = LocalDensity.current.density

    fun rowHeightOf(id: String): Float = rowHeights[id] ?: run {
        val mergedTagCount = lifelines.firstOrNull { it.id == id }?.tagIds?.size ?: 1
        val baseDp = 64f
        val mergedBlockDp = if (mergedTagCount > 1) 20f + mergedTagCount * 24f else 0f
        (baseDp + mergedBlockDp) * rowDensity
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(ADD_HINT_DURATION_MS)
            hint = null
        }
    }
    LaunchedEffect(lifelines) {
        val validIds = lifelines.mapTo(hashSetOf()) { it.id }
        view.selectedLifelineIds = view.selectedLifelineIds.filterTo(linkedSetOf()) { it in validIds }
        if (view.selectedLifelineId !in validIds) view.selectedLifelineId = null
        if (view.hoveredLifelineId !in validIds) view.hoveredLifelineId = null
        if (editingId !in validIds) editingId = null
    }
    LaunchedEffect(lifelineIds, dragId, justReleasedId) {
        if (shouldSyncSequenceVisualOrder(dragId, justReleasedId)) liveVisualIds = lifelineIds
    }
    LaunchedEffect(justReleasedId) {
        if (justReleasedId != null) {
            delay(120)
            justReleasedId = null
        }
    }
    val visualIds = liveVisualIds.takeIf { it.toSet() == lifelineIds.toSet() && it.size == lifelineIds.size } ?: lifelineIds
    val currentVisualIds = rememberUpdatedState(visualIds)
    val currentDragId = rememberUpdatedState(dragId)
    // pointerInput below is keyed on lifeline.id alone (stable across reorders) so an in-progress
    // drag isn't cancelled by the reorder it's causing — but that also means detectDragGestures'
    // coroutine is never restarted after the first drag on a given row, so any plain `val` it
    // closes over goes stale on every drag after the first. rememberUpdatedState keeps it reading
    // the current order — same reasoning as AnnotationPanel's own `currentBlockIds`.
    val currentLifelineIds = rememberUpdatedState(lifelineIds)
    val targetOffsets = cumulativeBlockOffsets(visualIds, ::rowHeightOf)
    val startOffsets = cumulativeBlockOffsets(lifelineIds, ::rowHeightOf)
    val totalHeightPx = lifelineIds.sumOf { rowHeightOf(it).toDouble() }.toFloat()

    fun startRename(lifeline: Seq3Lifeline) {
        editingId = lifeline.id
        editingText = lifeline.name
    }

    fun commitRename(lifelineId: String) {
        if (!state.seq3Sessions.applyCommand(session.id, Seq3Command.RenameLifeline(lifelineId, editingText))) {
            hint = "Enter a non-empty lifeline name"
        }
        editingId = null
    }

    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Lifelines · ${lifelines.size}",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Diagram-wide default for every lifeline whose own "name ▾" is "Diagram
                    // default" (item 3's second half) — otherwise that per-lifeline option would
                    // have nothing to inherit.
                    Seq3DropdownButton(
                        label = "names: ${seq3DocumentDisplaySegmentsLabel(document.lifelineDisplaySegments)}",
                        labelColor = tc.ts,
                        fillColor = tc.p2,
                        menuWidth = 150.dp,
                    ) { close ->
                        SEQ3_DOCUMENT_DISPLAY_SEGMENT_OPTIONS.forEach { (label, value) ->
                            Seq3DropdownMenuItem(label, active = document.lifelineDisplaySegments == value) {
                                state.seq3Sessions.applyCommand(session.id, Seq3Command.SetDocumentDisplaySegments(value))
                                close()
                            }
                        }
                    }
                    LabelIconButton(
                        text = "+ lifeline",
                        fontSize = 10.sp,
                        onClick = { addDialogOpen = true },
                    )
                }
            },
            expanded = view.lifelinesExpanded,
            onToggle = { view.lifelinesExpanded = !view.lifelinesExpanded },
        )
        hint?.let { AppText(it, color = tc.warn, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
        if (view.lifelinesExpanded) {
            // WP8 item 4: the Merge/Clear footer used to be the last child of this scrolling
            // Column, so with more than a handful of lifelines it sat below the fold and checking
            // two lifelines showed no Merge button at all. Pin it as an unweighted sibling below
            // the scroll area instead — same "weighted Box owns the scroll, footer follows
            // unweighted" shape Messages uses for `Seq3QueueFooter` above. `selected` is hoisted
            // here (out of the scrolling content) so both this Box and the footer below can read
            // it without either depending on the other's composition.
            val selected = lifelines.filter { it.id in view.selectedLifelineIds }
            val lifelinesScrollState = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(lifelinesScrollState).padding(end = 6.dp),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = (totalHeightPx / rowDensity).dp)) {
                        lifelines.forEach { lifeline ->
                            key(lifeline.id) {
                                val isDragging = dragId == lifeline.id
                                val targetY = targetOffsets[lifeline.id] ?: 0f
                                val pointerY = (startOffsets[lifeline.id] ?: 0f) + dragOffsetY
                                val y = sequenceRenderY(
                                    isDragging = isDragging,
                                    isJustReleased = justReleasedId == lifeline.id,
                                    pointerY = pointerY,
                                    targetY = targetY,
                                    animatedY = targetY,
                                )
                                val dragHandleModifier = Modifier.pointerInput(lifeline.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragId = lifeline.id
                                            dragOffsetY = 0f
                                            justReleasedId = null
                                            liveVisualIds = currentLifelineIds.value
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragOffsetY += delta.y
                                            liveVisualIds = blockOrderDuringDrag(
                                                visibleIds = currentLifelineIds.value,
                                                draggedId = dragId,
                                                dragOffsetY = dragOffsetY,
                                                heightOf = ::rowHeightOf,
                                            )
                                        },
                                        onDragEnd = {
                                            val releasedId = currentDragId.value ?: lifeline.id
                                            val releasedOrder = currentVisualIds.value
                                            if (releasedOrder != currentLifelineIds.value) {
                                                state.seq3Sessions.applyCommand(session.id, Seq3Command.ReorderLifelines(releasedOrder))
                                            }
                                            justReleasedId = releasedId
                                            dragId = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            dragId = null
                                            dragOffsetY = 0f
                                        },
                                    )
                                }
                                Box(
                                    Modifier.fillMaxWidth()
                                        .offset { IntOffset(0, y.roundToInt()) }
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            if (isDragging) {
                                                scaleX = 1.02f
                                                scaleY = 1.02f
                                            }
                                        }
                                        .onSizeChanged { size -> rowHeights[lifeline.id] = size.height.toFloat() }
                                        .background(if (isDragging) tc.p else Color.Transparent),
                                ) {
                                    Seq3LifelineRow(
                                        state = state,
                                        session = session,
                                        view = view,
                                        document = document,
                                        lifeline = lifeline,
                                        editing = editingId == lifeline.id,
                                        editingText = editingText,
                                        onEditingText = { editingText = it },
                                        onCommitRename = { commitRename(lifeline.id) },
                                        onCancelRename = { editingId = null },
                                        onRename = { startRename(lifeline) },
                                        onRemove = {
                                            if (!state.seq3Sessions.applyCommand(session.id, Seq3Command.RemoveLifeline(lifeline.id))) {
                                                hint = "Reassign source messages before removing this lifeline"
                                            } else {
                                                view.selectedLifelineIds = view.selectedLifelineIds - lifeline.id
                                                if (view.selectedLifelineId == lifeline.id) view.selectedLifelineId = null
                                            }
                                        },
                                        onMoveTagOut = { tagId ->
                                            val newId = "seq3-lifeline-${UUID.randomUUID()}"
                                            val applied = state.seq3Sessions.applyCommand(
                                                session.id,
                                                Seq3Command.SplitLifeline(
                                                    lifelineId = lifeline.id,
                                                    tagId = tagId,
                                                    newLifeline = Seq3Lifeline(
                                                        id = newId,
                                                        name = tagId,
                                                        tagIds = setOf(tagId),
                                                        ordinal = lifeline.ordinal + 1,
                                                        visibility = lifeline.visibility,
                                                    ),
                                                ),
                                            )
                                            if (!applied) hint = "This tag cannot be moved out"
                                        },
                                        dragHandleModifier = dragHandleModifier,
                                        isDragging = isDragging,
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lifelinesScrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                    style = appScrollbarStyle(tc),
                )
            }
            if (selected.size >= 2) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("${selected.size} selected", color = tc.ts, fontSize = 10.sp)
                    ToolbarBtn(
                        label = "Merge",
                        tooltip = "Merge selected lifelines into the first selected lifeline",
                        active = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        onClick = {
                            val ordered = selected.sortedBy { it.ordinal }
                            val keep = ordered.first()
                            val merged = ordered.drop(1).mapTo(linkedSetOf()) { it.id }
                            if (state.seq3Sessions.applyCommand(session.id, Seq3Command.MergeLifelineSelection(keep.id, merged))) {
                                view.selectedLifelineIds = emptySet()
                                view.selectedLifelineId = keep.id
                            } else {
                                hint = "The lifelines could not be merged"
                            }
                        },
                    )
                    ToolbarBtn(
                        label = "Clear",
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        onClick = { view.selectedLifelineIds = emptySet() },
                    )
                }
            }
        }
    }
    if (addDialogOpen) {
        Seq3LifelineNameDialog(
            onDismiss = { addDialogOpen = false },
            onAdd = { name ->
                addDialogOpen = false
                val ordinal = (document.lifelines.maxOfOrNull { it.ordinal } ?: -1) + 1
                val added = state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.AddLifeline(
                        Seq3Lifeline(
                            id = "seq3-lifeline-${UUID.randomUUID()}",
                            name = name,
                            // Deliberately still empty, not e.g. setOf(name): `dispatchAddLifeline`
                            // is the one place that owns "what a manual lifeline's represented tag
                            // defaults to" (setOf(the TRIMMED name) — see its own doc), and it
                            // trims `name` itself before deriving that default. Pre-filling
                            // setOf(name) here with the untrimmed dialog input would risk a
                            // trim/tagIds mismatch dispatchAddLifeline's own trim-then-default
                            // ordering avoids. Before this fix an empty set here meant the new
                            // lifeline contributed nothing to a later merge's tagIds fold (item 8);
                            // now it is defaulted downstream instead of left empty.
                            tagIds = emptySet(),
                            ordinal = ordinal,
                        ),
                    ),
                )
                if (!added) hint = "Enter a non-empty, unique lifeline name"
            },
        )
    }
}

/** Rebuilt against [Seq3QueueRow] (item 1) — same container recipe, checkbox gutter, two-line
 *  title/controls body, and trailing state word. [dragHandleModifier] carries the section's
 *  `detectDragGestures` (item 4); this composable itself owns no drag state. */
@Composable
private fun Seq3LifelineRow(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    lifeline: Seq3Lifeline,
    editing: Boolean,
    editingText: String,
    onEditingText: (String) -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onMoveTagOut: (String) -> Unit,
    dragHandleModifier: Modifier,
    // Unused inside this composable (the caller already applies the drag scale/zIndex/background
    // itself — see this doc's own "owns no drag state" note above) but kept in the signature to
    // mirror the caller's full drag-state contract for this row, the same shape a future in-row
    // drag affordance would need.
    @Suppress("UnusedParameter") isDragging: Boolean,
) {
    val tc = tc()
    val selected = lifeline.id in view.selectedLifelineIds
    val focused = view.selectedLifelineId == lifeline.id
    val hovered = view.hoveredLifelineId == lifeline.id
    val hidden = lifeline.visibility == Seq3Visibility.HIDDEN
    val messageCount = document.messages.count { it.fromLifelineId == lifeline.id || it.toLifelineId == lifeline.id }
    // Item 8's "manual" derivation, post-WP1: every manual lifeline now carries `tagIds =
    // setOf(name)` (`dispatchAddLifeline`'s own doc), so `tagIds.isEmpty()` can no longer tell a
    // manual lifeline apart from a generated one. What still can: a manual lifeline has never had
    // any real log evidence attached to a message that references it (an authored/custom message
    // is always added with `occurrences = emptyList()` — see `addSeq3CustomMessage`), while a
    // generated lifeline always backs at least one message with real occurrences.
    val hasOccurrenceBacking = document.messages.any {
        (it.fromLifelineId == lifeline.id || it.toLifelineId == lifeline.id) && it.occurrences.isNotEmpty()
    }
    val displayName = seq3DisplayName(lifeline.name, lifeline.displaySegments, document.lifelineDisplaySegments)

    fun toggleChecked() {
        view.selectedLifelineIds = if (lifeline.id in view.selectedLifelineIds) {
            view.selectedLifelineIds - lifeline.id
        } else {
            view.selectedLifelineIds + lifeline.id
        }
        runCatching { view.focusRequester.requestFocus() }
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(CORNER_SM)
            .background(
                when {
                    focused -> tc.abg
                    selected -> tc.sl
                    hovered -> tc.hv
                    else -> Color.Transparent
                },
                CORNER_SM,
            )
            .then(if (focused) Modifier.border(1.dp, tc.ac, CORNER_SM) else Modifier)
            .drawBehind {
                if (selected) drawRect(color = tc.ac, size = Size(3.dp.toPx(), size.height))
            }
            .onPointerEvent(PointerEventType.Enter) { view.hoveredLifelineId = lifeline.id }
            .onPointerEvent(PointerEventType.Exit) { if (view.hoveredLifelineId == lifeline.id) view.hoveredLifelineId = null }
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText(
                "⠿",
                color = tc.td,
                fontSize = 12.sp,
                modifier = dragHandleModifier.padding(top = 2.dp)
                    .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.MOVE_CURSOR))),
            )
            Box(Modifier.width(16.dp).padding(top = 2.dp)) {
                Seq3RowCheckbox(checked = selected) { toggleChecked() }
            }
            if (editing) {
                InlineField(
                    value = editingText,
                    onValue = onEditingText,
                    // WP7 item 6: this rename field was missing the textFieldFocused wiring every
                    // other rename editor in the workspace already carries — without it, typing a
                    // lifeline name here would fire the canvas's single-letter shortcut map exactly
                    // like the canvas chip's own editor did before this WP.
                    modifier = Modifier.weight(1f).height(24.dp).onFocusChanged { view.textFieldFocused = it.hasFocus },
                    fontSize = 11.sp,
                    onSubmit = onCommitRename,
                    onCancel = onCancelRename,
                )
                SquareIconButton("✓", fontSize = 11.sp, onClick = onCommitRename, size = 20.dp)
                SquareIconButton("×", fontSize = 13.sp, onClick = onCancelRename, size = 20.dp)
            } else {
                Column(
                    Modifier.weight(1f).pointerInput(lifeline.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                                    event.changes.none { it.isConsumed }
                                ) {
                                    // Item 4 (WP-panel-toggle): same toggle-to-deselect as the
                                    // fragment/note rows in Seq3FragmentsAndNotesSection.
                                    seq3ToggleLifelineSelection(view, lifeline.id)
                                    runCatching { view.focusRequester.requestFocus() }
                                }
                            }
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Title line — mirrors Seq3RowPatternLine: display name left, ×N right.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            displayName,
                            color = if (!hidden) tc.tx else tc.ts,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (hidden) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f),
                        )
                        if (messageCount > 0) AppText("×$messageCount", color = tc.ts, fontSize = 10.sp)
                    }
                    // Controls line — mirrors Seq3MessageControlsLine: every control at the shared
                    // 24dp badge size, contentPadding 0, CORNER_SM shape.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolbarBtn(
                            label = if (hidden) "Show lifeline" else "Hide lifeline",
                            icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            showLabel = false,
                            tooltip = if (hidden) "Show lifeline" else "Hide lifeline",
                            active = hidden,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
                            shape = CORNER_SM,
                            onClick = {
                                state.seq3Sessions.applyCommand(
                                    session.id,
                                    Seq3Command.SetLifelineVisibility(
                                        lifeline.id,
                                        if (hidden) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                                    ),
                                )
                            },
                        )
                        ToolbarBtn(
                            label = "✎",
                            tooltip = "Rename lifeline",
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
                            shape = CORNER_SM,
                            onClick = onRename,
                        )
                        Seq3DropdownButton(
                            label = seq3LifelineDisplaySegmentsLabel(lifeline.displaySegments),
                            labelColor = tc.ts,
                            fillColor = tc.p2,
                            menuWidth = 150.dp,
                            fixedHeight = SEQ3_ACTION_BADGE_SIZE,
                        ) { close ->
                            SEQ3_LIFELINE_DISPLAY_SEGMENT_OPTIONS.forEach { (label, value) ->
                                Seq3DropdownMenuItem(label, active = lifeline.displaySegments == value) {
                                    state.seq3Sessions.applyCommand(session.id, Seq3Command.SetLifelineDisplaySegments(lifeline.id, value))
                                    close()
                                }
                            }
                        }
                        Seq3DropdownButton(
                            label = lifeline.kind.name.lowercase(),
                            labelColor = tc.ts,
                            fillColor = tc.p2,
                            menuWidth = 110.dp,
                            fixedHeight = SEQ3_ACTION_BADGE_SIZE,
                        ) { close ->
                            Seq3LifelineKind.entries.forEach { kind ->
                                Seq3DropdownMenuItem(kind.name.lowercase(), active = kind == lifeline.kind) {
                                    state.seq3Sessions.applyCommand(session.id, Seq3Command.SetLifelineKind(lifeline.id, kind))
                                    close()
                                }
                            }
                        }
                        ToolbarBtn(
                            label = "×",
                            tooltip = "Remove lifeline",
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
                            shape = CORNER_SM,
                            onClick = onRemove,
                        )
                        Spacer(Modifier.weight(1f))
                        Seq3LifelineStateWord(hidden = hidden, mergedCount = lifeline.tagIds.size, manual = !hasOccurrenceBacking)
                    }
                }
            }
        }
        if (!editing && lifeline.tagIds.size > 1) {
            Column(
                Modifier.fillMaxWidth().padding(start = 38.dp, top = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                AppText("Merged lifelines", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                lifeline.tagIds.sorted().forEach { tagId ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(CORNER_SM)
                            .background(tc.p2, CORNER_SM)
                            .border(1.dp, tc.br, CORNER_SM)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(tagId, color = tc.tx, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        SquareIconButton(
                            "↗",
                            fontSize = 10.sp,
                            onClick = { onMoveTagOut(tagId) },
                            size = 16.dp,
                        )
                    }
                }
            }
        }
    }
}

/** [Seq3StateWord]'s lifeline counterpart — `hidden` / `merged · N` / `manual` / `auto`, in that
 *  priority order (a hidden merged lifeline still reads "hidden", matching how a hidden message
 *  reads "hidden" regardless of its own edited/needs-target state). */
@Composable
private fun Seq3LifelineStateWord(hidden: Boolean, mergedCount: Int, manual: Boolean) {
    val tc = tc()
    val (label, color) = when {
        hidden -> "hidden" to tc.td
        mergedCount > 1 -> "merged · $mergedCount" to tc.ac
        manual -> "manual" to tc.ts
        else -> "auto" to tc.ts
    }
    AppText(
        label.uppercase(),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        textDecoration = if (hidden) TextDecoration.LineThrough else null,
    )
}

@Composable
private fun Seq3LifelineNameDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val tc = tc()
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(360.dp).clip(RoundedCornerShape(12.dp))
                .background(tc.p, RoundedCornerShape(12.dp))
                .border(1.dp, tc.br, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Add lifeline", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText("Create an empty lifeline for a manual or unresolved message.", color = tc.ts, fontSize = 11.sp)
            InlineField(name, { name = it }, placeholder = "Lifeline name…", modifier = Modifier.fillMaxWidth(), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
                AppButton("Add lifeline", onClick = { onAdd(name.trim()) }, enabled = name.isNotBlank(), variant = ButtonVariant.Primary)
            }
        }
    }
}

/** Resolves a log tab's currently-selected row ids into the [LogEntry]s "Add ＋" (item 2) hands to
 *  [addSeq3MessageFromSelection] — pure and `internal` purely for testability, mirroring
 *  [seq3PinnableDirections]/[seq3TemplateSegments]'s own "no composition needed" split. */
internal fun seq3ResolveSelectedEntries(logData: List<LogEntry>, selectedIds: Set<Int>): List<LogEntry> =
    logData.filter { it.id in selectedIds }

/** Accepts `12`, `12, 15`, and `12-15` so the add flow can use log row ids without another picker. */
internal fun seq3ParseRowNumbers(raw: String): List<Int> = raw
    .split(',', ';', ' ', '\n', '\t')
    .mapNotNull { token ->
        val value = token.trim()
        if (value.isEmpty()) return@mapNotNull null
        val parts = value.split('-', limit = 2)
        if (parts.size == 1) {
            parts[0].toIntOrNull()?.let(::listOf)
        } else {
            val first = parts[0].toIntOrNull()
            val last = parts[1].toIntOrNull()
            if (first == null || last == null) {
                null
            } else {
                val start = minOf(first, last)
                val end = minOf(maxOf(first, last).toLong(), start.toLong() + ADD_ROW_RANGE_LIMIT - 1).toInt()
                (start..end).toList()
            }
        }
    }
    .flatten()
    .distinct()

@Composable
private fun Seq3QueueHeader(
    state: AppState,
    session: Seq3WorkspaceSession,
    counts: com.indagium.diagram3.Seq3FilterCounts,
    view: Seq3ViewState,
) {
    val tc = tc()
    val density = LocalDensity.current
    // A short-lived rejection hint ("Select rows from a single tag", …) — this file has no other
    // transient-message convention to match (checked, per this phase's brief), so a plain
    // remember+LaunchedEffect auto-clear is the documented fallback.
    var addHint by remember(session.id) { mutableStateOf<String?>(null) }
    var addMenuOpen by remember(session.id) { mutableStateOf(false) }
    var addDialog by remember(session.id) { mutableStateOf<Seq3AddDialog?>(null) }
    LaunchedEffect(addHint) {
        if (addHint != null) {
            delay(ADD_HINT_DURATION_MS)
            addHint = null
        }
    }

    fun addFromEntries(selectedEntries: List<LogEntry>) {
        when (val result = addSeq3MessageFromSelection(session.document, selectedEntries)) {
            is Seq3AddResult.Added ->
                state.seq3Sessions.applyCommand(session.id, Seq3Command.ReplaceDocument(result.document))
            is Seq3AddResult.Rejected -> addHint = result.reason
        }
    }

    val tab = session.sourceTabId?.let(state::tab)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Messages · ${counts.all}",
                trailing = {
                    // The action stays on the shared 32dp header rhythm, but opens a visible
                    // choice surface instead of silently assuming the log selection is correct.
                    LabelIconButton(
                        text = "+ message",
                        fontSize = 10.sp,
                        onClick = { addMenuOpen = !addMenuOpen },
                        modifier = Modifier.pointerHoverIcon(
                            PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                            overrideDescendants = true,
                        ),
                    )
                },
                expanded = view.messagesExpanded,
                onToggle = { view.messagesExpanded = !view.messagesExpanded },
            )
            if (addMenuOpen) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = with(density) { IntOffset(0, 34.dp.roundToPx()) },
                    onDismissRequest = { addMenuOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Seq3AddMessageChoiceMenu(
                        selectedCount = tab?.selected?.size ?: 0,
                        onSelectedRows = {
                            addMenuOpen = false
                            addFromEntries(tab?.let { seq3ResolveSelectedEntries(it.logData, it.selected) }.orEmpty())
                        },
                        onRows = {
                            addMenuOpen = false
                            addDialog = Seq3AddDialog.ROWS
                        },
                        onLogHandoff = {
                            addMenuOpen = false
                            session.sourceTabId?.let(state::activateTab)
                        },
                        onCustom = {
                            addMenuOpen = false
                            addDialog = Seq3AddDialog.CUSTOM
                        },
                    )
                }
            }
        }
        addHint?.let { hint ->
            AppText(hint, color = tc.warn, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
    when (addDialog) {
        Seq3AddDialog.ROWS -> Seq3AddRowsDialog(
            entries = tab?.logData.orEmpty(),
            onDismiss = { addDialog = null },
            onAdd = { entries ->
                addDialog = null
                addFromEntries(entries)
            },
        )
        Seq3AddDialog.CUSTOM -> Seq3AddCustomDialog(
            document = session.document,
            onDismiss = { addDialog = null },
            onAdd = { spec ->
                addDialog = null
                if (!state.seq3Sessions.applyCommand(session.id, Seq3Command.AddCustomMessage(spec))) {
                    addHint = "The custom message could not be added"
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun Seq3AddMessageChoiceMenu(
    selectedCount: Int,
    onSelectedRows: () -> Unit,
    onRows: () -> Unit,
    onLogHandoff: () -> Unit,
    onCustom: () -> Unit,
) {
    val tc = tc()
    Column(
        Modifier.width(238.dp).clip(RoundedCornerShape(8.dp))
            .background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        AppText("Add message from…", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
        AppButton(
            "Selected log rows · $selectedCount",
            onClick = onSelectedRows,
            enabled = selectedCount > 0,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        AppButton(
            "Enter row numbers…",
            onClick = onRows,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        AppButton(
            "Open log view to select rows",
            onClick = onLogHandoff,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        AppButton(
            "Custom message…",
            onClick = onCustom,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        // User-observed correction: "Insert delay marker" used to live here, duplicating the
        // Artifacts panel's own "+ delay" button (which a delay's Seq3ArtifactRow now belongs
        // next to, matching the model's own "closer to Note than to Message" design — see
        // Seq3Model.kt's Seq3Delay header) and the canvas context menu's "Insert delay after
        // this" (still there, for anchoring to an arbitrary row rather than always the last one).
        // This menu is strictly "add a message from…" now; a delay isn't one.
    }
}

@Composable
private fun Seq3AddRowsDialog(entries: List<LogEntry>, onDismiss: () -> Unit, onAdd: (List<LogEntry>) -> Unit) {
    val tc = tc()
    var raw by remember { mutableStateOf("") }
    val selected = remember(raw, entries) {
        val ids = seq3ParseRowNumbers(raw).toSet()
        entries.filter { it.id in ids }
    }
    val hasInput = raw.isNotBlank()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(430.dp).clip(RoundedCornerShape(12.dp))
                .background(tc.p, RoundedCornerShape(12.dp))
                .border(1.dp, tc.br, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Add message from log rows", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText("Enter row numbers or ranges, for example 12, 14-16.", color = tc.ts, fontSize = 11.sp)
            InlineField(raw, { raw = it }, placeholder = "Row numbers…", modifier = Modifier.fillMaxWidth(), fontSize = 12.sp)
            AppText(
                when {
                    !hasInput -> ""
                    selected.isEmpty() -> "No matching rows"
                    else -> "${selected.size} row${if (selected.size == 1) "" else "s"} selected"
                },
                color = if (selected.isEmpty() && hasInput) tc.warn else tc.ts,
                fontSize = 10.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
                AppButton("Add message", onClick = { onAdd(selected) }, enabled = selected.isNotEmpty(), variant = ButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun Seq3AddCustomDialog(
    document: Seq3Document,
    onDismiss: () -> Unit,
    onAdd: (Seq3CustomMessageSpec) -> Unit,
) {
    val tc = tc()
    var message by remember { mutableStateOf("") }
    var fromId by remember(document) { mutableStateOf(document.lifelines.firstOrNull()?.id) }
    var toId by remember(document) { mutableStateOf(document.lifelines.getOrNull(1)?.id ?: document.lifelines.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(Seq3Kind.CALL) }
    var timestamp by remember { mutableStateOf("") }
    var positionMode by remember { mutableStateOf(Seq3CustomPositionMode.END) }
    var positionValue by remember { mutableStateOf("") }
    var fragmentId by remember { mutableStateOf<String?>(null) }

    val position = when (positionMode) {
        Seq3CustomPositionMode.START -> Seq3InsertionPosition.Start
        Seq3CustomPositionMode.END -> Seq3InsertionPosition.End
        Seq3CustomPositionMode.BEFORE -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { Seq3InsertionPosition.BeforeMessage(it.id) }
        Seq3CustomPositionMode.AFTER -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { Seq3InsertionPosition.AfterMessage(it.id) }
        Seq3CustomPositionMode.INDEX -> positionValue.toIntOrNull()?.let(Seq3InsertionPosition::AtIndex)
    }
    val validEndpoints = fromId != null && (kind == Seq3Kind.NOTE || toId != null)
    val canAdd = message.isNotBlank() && validEndpoints && position != null &&
        (position !is Seq3InsertionPosition.AtIndex || position.index in 0..document.messages.size)

    fun buildSpec(): Seq3CustomMessageSpec? {
        val selectedFrom = fromId ?: return null
        val selectedPosition = position ?: return null
        return Seq3CustomMessageSpec(
            fromLifelineId = selectedFrom,
            toLifelineId = if (kind == Seq3Kind.NOTE) null else toId,
            text = message,
            timestampMillis = parseSeq3Timestamp(timestamp),
            rawTimestamp = timestamp,
            position = selectedPosition,
            kind = kind,
            fragmentId = fragmentId,
        )
    }

    fun chooseKind(next: Seq3Kind) {
        kind = next
        when (next) {
            Seq3Kind.SELF -> toId = fromId
            Seq3Kind.NOTE -> toId = null
            else -> if (toId == null) toId = document.lifelines.firstOrNull { it.id != fromId }?.id ?: fromId
        }
    }

    val positionLabel = when (positionMode) {
        Seq3CustomPositionMode.START -> "At start"
        Seq3CustomPositionMode.END -> "At end"
        Seq3CustomPositionMode.INDEX -> "At index ${positionValue.ifBlank { "…" }}"
        Seq3CustomPositionMode.BEFORE -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { "Before ${document.messages.indexOf(it) + 1}: ${it.labelTemplate}" } ?: "Before message…"
        Seq3CustomPositionMode.AFTER -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { "After ${document.messages.indexOf(it) + 1}: ${it.labelTemplate}" } ?: "After message…"
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(600.dp).clip(RoundedCornerShape(12.dp))
                .background(tc.p, RoundedCornerShape(12.dp))
                .border(1.dp, tc.br, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Add custom message", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Seq3CustomLifelinePicker("From", document, fromId, Modifier.weight(1f)) { fromId = it; if (kind == Seq3Kind.SELF) toId = it }
                if (kind != Seq3Kind.NOTE && kind != Seq3Kind.SELF) {
                    Seq3CustomLifelinePicker("To", document, toId, Modifier.weight(1f)) { toId = it }
                }
            }
            AppText("Kind", color = tc.td, fontSize = 10.sp)
            SegmentedControl(
                options = listOf("call", "return", "async", "self", "note"),
                selectedIndices = setOf(Seq3Kind.entries.indexOf(kind)),
                onToggle = { chooseKind(Seq3Kind.entries[it]) },
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InlineField(
                    timestamp,
                    { timestamp = it },
                    placeholder = "Timestamp (optional, e.g. 09:15:16.500)",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                )
                Seq3DropdownButton(positionLabel, modifier = Modifier.weight(1f), menuWidth = 330.dp) { close ->
                    Seq3DropdownMenuItem("At start", active = positionMode == Seq3CustomPositionMode.START) {
                        positionMode = Seq3CustomPositionMode.START; positionValue = ""; close()
                    }
                    Seq3DropdownMenuItem("At end", active = positionMode == Seq3CustomPositionMode.END) {
                        positionMode = Seq3CustomPositionMode.END; positionValue = ""; close()
                    }
                    Seq3DropdownMenuItem("At exact index (0 = first)", active = positionMode == Seq3CustomPositionMode.INDEX) {
                        positionMode = Seq3CustomPositionMode.INDEX; positionValue = ""; close()
                    }
                    document.messages.forEachIndexed { index, candidate ->
                        Seq3DropdownMenuItem(
                            "Before ${index + 1}: ${candidate.labelTemplate}",
                            active = positionMode == Seq3CustomPositionMode.BEFORE && positionValue == candidate.id,
                        ) {
                            positionMode = Seq3CustomPositionMode.BEFORE; positionValue = candidate.id; close()
                        }
                        Seq3DropdownMenuItem(
                            "After ${index + 1}: ${candidate.labelTemplate}",
                            active = positionMode == Seq3CustomPositionMode.AFTER && positionValue == candidate.id,
                        ) {
                            positionMode = Seq3CustomPositionMode.AFTER; positionValue = candidate.id; close()
                        }
                    }
                }
            }
            if (positionMode == Seq3CustomPositionMode.INDEX) {
                InlineField(
                    positionValue,
                    { positionValue = it.filter(Char::isDigit) },
                    placeholder = "Insertion index…",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                )
            }
            Seq3DropdownButton(
                label = fragmentId?.let { id ->
                    document.fragments.firstOrNull { it.id == id }?.let { f -> "${f.kind.name.lowercase()}: ${f.label}" }
                } ?: "No fragment",
                modifier = Modifier.fillMaxWidth(),
                menuWidth = 430.dp,
            ) { close ->
                Seq3DropdownMenuItem("No fragment", active = fragmentId == null) { fragmentId = null; close() }
                document.fragments.forEach { fragment ->
                    Seq3DropdownMenuItem("${fragment.kind.name.lowercase()}: ${fragment.label}", active = fragment.id == fragmentId) {
                        fragmentId = fragment.id; close()
                    }
                }
            }
            InlineField(
                message,
                { message = it },
                placeholder = "Message text…",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                onSubmit = { if (canAdd) buildSpec()?.let(onAdd) },
            )
            if (!canAdd) {
                AppText(
                    when {
                        message.isBlank() -> "Enter message text"
                        !validEndpoints -> "Choose both From and To lifelines"
                        position == null -> "Choose a valid insertion position"
                        else -> ""
                    },
                    color = tc.warn,
                    fontSize = 10.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Spacer(Modifier.weight(1f))
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
                AppButton(
                    "Add message",
                    onClick = { buildSpec()?.let(onAdd) },
                    enabled = canAdd,
                    variant = ButtonVariant.Primary,
                )
            }
        }
    }
}

@Composable
private fun Seq3CustomLifelinePicker(
    title: String,
    document: Seq3Document,
    selectedId: String?,
    modifier: Modifier,
    onSelected: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(title, color = tc().td, fontSize = 10.sp)
        Seq3DropdownButton(
            label = document.lifelines.firstOrNull { it.id == selectedId }?.name ?: "Choose lifeline",
            modifier = Modifier.fillMaxWidth(),
            menuWidth = 240.dp,
        ) { close ->
            document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                Seq3DropdownMenuItem(lifeline.name, active = lifeline.id == selectedId) {
                    onSelected(lifeline.id)
                    close()
                }
            }
        }
    }
}

@Composable
private fun Seq3NeedsTargetBanner(count: Int, onFixThese: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.warnBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("$count message${if (count == 1) "" else "s"} need a target", color = tc.warn, fontSize = 11.sp)
        AppButton(
            "Fix these →",
            onClick = onFixThese,
            variant = ButtonVariant.Ghost,
            textColor = tc.warn,
            horizontalPadding = 0.dp,
            modifier = Modifier.pointerHoverIcon(
                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                overrideDescendants = true,
            ),
        )
    }
}

/** Formats a millisecond gap the way a human would say it out loud — "42s", "3m 5s" — never
 *  `[#n] [ts]`-style HH:MM:SS.mmm (this is a DURATION, not a point in time, so that format would
 *  misleadingly imply precision this coarse threshold doesn't have). */
private fun seq3FormatGapDuration(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

@Composable
private fun Seq3DelaySuggestionBanner(
    suggestion: Seq3DelaySuggestion,
    moreCount: Int,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.abg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val suffix = if (moreCount > 0) " (+$moreCount more)" else ""
        AppText(
            "${seq3FormatGapDuration(suggestion.gapMillis)} gap detected$suffix — add a delay marker?",
            color = tc.ac,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppButton("Insert", onClick = onInsert, variant = ButtonVariant.Ghost, textColor = tc.ac, horizontalPadding = 4.dp)
            AppButton("Dismiss", onClick = onDismiss, variant = ButtonVariant.Ghost, textColor = tc.td, horizontalPadding = 4.dp)
        }
    }
}

@Composable
private fun Seq3FilterChipsRow(view: Seq3ViewState, counts: com.indagium.diagram3.Seq3FilterCounts) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PillBtn("All ${counts.all}", active = view.filter == Seq3Filter.ALL) { view.filter = Seq3Filter.ALL }
        PillBtn("Needs target ${counts.needsTarget}", active = view.filter == Seq3Filter.NEEDS_TARGET) { view.filter = Seq3Filter.NEEDS_TARGET }
        PillBtn("Edited ${counts.edited}", active = view.filter == Seq3Filter.EDITED) { view.filter = Seq3Filter.EDITED }
        PillBtn("Hidden ${counts.hidden}", active = view.filter == Seq3Filter.HIDDEN) { view.filter = Seq3Filter.HIDDEN }
    }
}

private val SORT_LABELS = mapOf(
    Seq3Sort.LOG_ORDER to "Log order",
    Seq3Sort.LIFELINE to "By lifeline",
    Seq3Sort.OCCURRENCES to "By occurrence count",
    Seq3Sort.STATE to "By state",
)

@Composable
private fun Seq3FilterTextAndSortRow(view: Seq3ViewState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineField(
            value = view.textFilter,
            onValue = { view.textFilter = it },
            placeholder = "Filter messages…",
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            onClear = if (view.textFilter.isNotEmpty()) { { view.textFilter = "" } } else {
                null
            },
        )
        // Sort is a VIEW, never an edit (spec §07) — Seq3QueuePanel's own header — so this only
        // ever writes `view.sort`, never dispatches a Seq3Command.
        Seq3DropdownButton(label = SORT_LABELS.getValue(view.sort), menuWidth = 176.dp) { close ->
            Seq3Sort.entries.forEach { option ->
                Seq3DropdownMenuItem(SORT_LABELS.getValue(option), active = option == view.sort) {
                    view.sort = option
                    close()
                }
            }
        }
    }
}

@Composable
private fun Seq3QueueFooter(counts: com.indagium.diagram3.Seq3FilterCounts, onRegenerate: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("${counts.all} messages · ${counts.needsTarget} need a target", color = tc.ts, fontSize = 11.sp)
        // Opens spec §08's review sheet — never a silent full rebuild, which is exactly what
        // "Regenerate is a reviewed proposal, never a wholesale replace" rules out.
        AppButton(
            "Regenerate…",
            onClick = onRegenerate,
            variant = ButtonVariant.Ghost,
            textColor = tc.ac,
            horizontalPadding = 0.dp,
            modifier = Modifier.pointerHoverIcon(
                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                overrideDescendants = true,
            ),
        )
    }
}

// ── Fragments & notes (item 3): visibility + rename for what `Group ▾`/`Note` create ───────────
//
// `Group ▾`/`Note` (in the selection action bar below) are structurally add-only — this is the
// missing edit-in-place counterpart: a compact expandable list surfacing every existing fragment/
// note by id with its current label/text, each double-click-to-edit inline (same convention as
// `Seq3Canvas.kt`'s `Seq3InlineLabelEditor`/`Seq3LifelineChip` label editors). Both rename actions
// are id-keyed, not selection-keyed (`Seq3BulkAction.SetFragmentLabel`/`SetNoteText`), so they route
// through `Seq3Command.Bulk(emptySet(), …)` — `applySeq3BulkAction`'s own empty-selection guard
// already exempts exactly these two actions (Seq3Queue.kt's own comment on that guard).

@Composable
private fun Seq3FragmentsAndNotesSection(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val total = document.fragments.size + document.notes.size + document.delays.size
    // WP8 3b (revised): "+ note" attaches to the current message selection, same as the title
    // bar's own "Note" action (Seq3TitleActionButton in Seq3Workspace.kt) — but when nothing is
    // selected it now falls back to a free-floating note at `seq3DefaultNotePlacement`'s default
    // position (same mechanism the canvas's own empty-context-menu "Add note here" uses), so this
    // button always succeeds. `hint` is kept only for the one genuine failure mode left: a stale
    // panel selection whose ids no longer resolve to any message in the document (`seq3AddNote`'s
    // own `ids.isEmpty() && placement == null` guard) — selectedIds non-empty but placement null.
    var hint by remember(session.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(ADD_HINT_DURATION_MS)
            hint = null
        }
    }
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            // User-observed correction: this used to enumerate "Fragments, notes & delays" —
            // every field/function backing this section is already named "artifact"
            // (artifactsVisible, artifactsExpanded, Seq3ArtifactRow) precisely so a fourth kind
            // added later doesn't need yet another word stitched onto the title.
            title = "Artifacts · $total",
            trailing = {
                // SectionHeader's whole Row sits inside a HoverBox(onClick = onToggle)
                // (Components.kt:222) — LabelIconButton's own `.clickable` consumes the tap
                // before it can bubble to that outer toggle, same as Lifelines' "+ lifeline".
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabelIconButton(
                        text = "+ note",
                        fontSize = 10.sp,
                        onClick = {
                            val selectedIds = seq3SelectedMessageIds(document, view)
                            val placement = if (selectedIds.isEmpty()) seq3DefaultNotePlacement(document) else null
                            if (!seq3AddNote(state, session, view, document, selectedIds, placement = placement)) {
                                hint = "Select a message first to attach a note to it"
                            }
                        },
                    )
                    // User-observed correction: removed the "+ delay" button this section briefly
                    // had — its only anchor logic ("after the latest selected message, else the
                    // document's end") wasn't precise enough to be trustworthy, and the canvas
                    // context menu's "Insert delay after this" (right-click the exact row) already
                    // covers creating one precisely. A delay's Seq3ArtifactRow below still lists,
                    // renames, hides, and removes every existing delay — only the ADD affordance
                    // moved back to being canvas-only.
                }
            },
            expanded = view.artifactsExpanded,
            onToggle = { view.artifactsExpanded = !view.artifactsExpanded },
        )
        hint?.let { AppText(it, color = tc.warn, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
        if (view.artifactsExpanded) {
            if (document.fragments.isEmpty() && document.notes.isEmpty() && document.delays.isEmpty()) {
                // WP8 (revised): the section now renders even with nothing to show (see
                // `artifactsVisible`'s own comment above) — replace the now-empty scrolling list
                // with a small hint so the section still claims its weighted section space
                // instead of collapsing to nothing.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    AppText("No artifacts yet", color = tc.td, fontSize = 10.sp)
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    document.fragments.forEach { fragment -> Seq3FragmentRenameRow(state, session, view, fragment) }
                    document.notes.forEach { note -> Seq3NoteRenameRow(state, session, view, note) }
                    document.delays.forEach { delayItem -> Seq3DelayRenameRow(state, session, view, delayItem) }
                }
            }
        }
    }
}

/** Every message id a fragment's bracket spans — [Seq3Fragment.occurrenceRefs] takes precedence
 *  for their own message ids over [Seq3Fragment.messageIds] (that field's own doc), so the two
 *  are unioned rather than either read alone. Mirrors `Seq3Queue.kt`'s private
 *  `applyGroup`'s own `referencedMessageIds` computation. */
internal fun seq3FragmentMessageCount(fragment: Seq3Fragment): Int =
    (fragment.messageIds + fragment.occurrenceRefs.map { it.messageId }).distinct().size

@Composable
private fun Seq3FragmentRenameRow(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, fragment: Seq3Fragment) {
    var editing by remember(fragment.id) { mutableStateOf(false) }
    var text by remember(fragment.id) { mutableStateOf(fragment.label) }

    fun commit() {
        state.seq3Sessions.applyCommand(
            session.id,
            Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentLabel(fragment.id, text)),
        )
        editing = false
    }
    Seq3ArtifactRow(
        id = fragment.id,
        kindWord = fragment.kind.name.lowercase(),
        label = fragment.label,
        messageCount = seq3FragmentMessageCount(fragment),
        hidden = fragment.visibility == Seq3Visibility.HIDDEN,
        selected = view.selectedFragmentId == fragment.id,
        hovered = view.hoveredFragmentId == fragment.id,
        onSelect = {
            // Item 4 (WP-panel-toggle): a second click on the already-selected row deselects it,
            // matching message selection's own click-again-to-deselect behavior.
            seq3ToggleFragmentSelection(view, fragment.id)
            runCatching { view.focusRequester.requestFocus() }
        },
        onHoverEnter = { view.hoveredFragmentId = fragment.id },
        onHoverExit = { if (view.hoveredFragmentId == fragment.id) view.hoveredFragmentId = null },
        editing = editing,
        editingText = text,
        onEditingText = { text = it },
        onCommitRename = ::commit,
        onCancelRename = { editing = false },
        onRename = { text = fragment.label; editing = true },
        onToggleVisibility = {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(
                    emptySet(),
                    Seq3BulkAction.SetFragmentVisibility(
                        fragment.id,
                        if (fragment.visibility == Seq3Visibility.HIDDEN) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                    ),
                ),
            )
        },
        onRemove = {
            state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteFragment(fragment.id)))
        },
        view = view,
        fragmentKind = fragment.kind,
        onSetFragmentKind = { kind ->
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentKind(fragment.id, kind)),
            )
        },
        hideKindLabel = fragment.hideKindLabel,
        onToggleHideKindLabel = {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentHideKindLabel(fragment.id, !fragment.hideKindLabel)),
            )
        },
    )
}

@Composable
private fun Seq3NoteRenameRow(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, note: Seq3Note) {
    var editing by remember(note.id) { mutableStateOf(false) }
    var text by remember(note.id) { mutableStateOf(note.text) }

    fun commit() {
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetNoteText(note.id, text)))
        editing = false
    }
    Seq3ArtifactRow(
        id = note.id,
        kindWord = "note",
        label = note.text,
        messageCount = note.messageIds.distinct().size,
        hidden = note.visibility == Seq3Visibility.HIDDEN,
        selected = view.selectedNoteId == note.id,
        hovered = view.hoveredNoteId == note.id,
        onSelect = {
            // Item 4 (WP-panel-toggle): same toggle-to-deselect as the fragment row above.
            seq3ToggleNoteSelection(view, note.id)
            runCatching { view.focusRequester.requestFocus() }
        },
        onHoverEnter = { view.hoveredNoteId = note.id },
        onHoverExit = { if (view.hoveredNoteId == note.id) view.hoveredNoteId = null },
        editing = editing,
        editingText = text,
        onEditingText = { text = it },
        onCommitRename = ::commit,
        onCancelRename = { editing = false },
        onRename = { text = note.text; editing = true },
        onToggleVisibility = {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(
                    emptySet(),
                    Seq3BulkAction.SetNoteVisibility(
                        note.id,
                        if (note.visibility == Seq3Visibility.HIDDEN) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                    ),
                ),
            )
        },
        onRemove = {
            state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteNote(note.id)))
        },
        view = view,
    )
}

/** The delay counterpart of [Seq3FragmentRenameRow]/[Seq3NoteRenameRow] above — same
 *  select/hover/rename/hide/remove recipe via [Seq3ArtifactRow], added when delays moved into
 *  this panel from being canvas/context-menu-only. [messageCount] is 0 (no "×N messages" badge):
 *  a delay is anchored to exactly one point in the timeline, not attached to a set of messages,
 *  so that badge — accurate for a fragment's span or a note's attachment — has nothing to count
 *  here. */
@Composable
private fun Seq3DelayRenameRow(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, delayItem: Seq3Delay) {
    var editing by remember(delayItem.id) { mutableStateOf(false) }
    var text by remember(delayItem.id) { mutableStateOf(delayItem.label) }

    fun commit() {
        if (text.isNotBlank()) {
            state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetDelayLabel(delayItem.id, text)))
        }
        editing = false
    }
    Seq3ArtifactRow(
        id = delayItem.id,
        kindWord = "delay",
        label = delayItem.label,
        messageCount = 0,
        hidden = delayItem.visibility == Seq3Visibility.HIDDEN,
        selected = view.selectedDelayId == delayItem.id,
        hovered = view.hoveredDelayId == delayItem.id,
        onSelect = {
            seq3ToggleDelaySelection(view, delayItem.id)
            runCatching { view.focusRequester.requestFocus() }
        },
        onHoverEnter = { view.hoveredDelayId = delayItem.id },
        onHoverExit = { if (view.hoveredDelayId == delayItem.id) view.hoveredDelayId = null },
        editing = editing,
        editingText = text,
        onEditingText = { text = it },
        onCommitRename = ::commit,
        onCancelRename = { editing = false },
        onRename = { text = delayItem.label; editing = true },
        onToggleVisibility = {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(
                    emptySet(),
                    Seq3BulkAction.SetDelayVisibility(
                        delayItem.id,
                        if (delayItem.visibility == Seq3Visibility.HIDDEN) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                    ),
                ),
            )
        },
        onRemove = {
            state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteDelay(delayItem.id)))
        },
        view = view,
    )
}

/** Shared row body for [Seq3FragmentRenameRow]/[Seq3NoteRenameRow] (item 9) — same container/
 *  title/controls recipe as [Seq3LifelineRow] and [Seq3QueueRow]. WP8 3a: this used to have a
 *  checkbox driving a bare local `remember` — no shared selection, no bulk action, no canvas
 *  link, and it wasn't clear what it did — so it is gone. The row body is clickable instead,
 *  same "Press event on a `pointerInput`-wrapped body Column" idiom [Seq3LifelineRow] already
 *  uses for its own body-click-to-toggle (`seq3ToggleLifelineSelection`); [selected]/
 *  [hovered]/[onSelect]/[onHoverEnter]/[onHoverExit] are supplied by the caller so this stays
 *  agnostic to whether it's drawing a fragment or a note. */
@Composable
private fun Seq3ArtifactRow(
    id: String,
    kindWord: String,
    label: String,
    messageCount: Int,
    hidden: Boolean,
    selected: Boolean,
    hovered: Boolean,
    onSelect: () -> Unit,
    onHoverEnter: () -> Unit,
    onHoverExit: () -> Unit,
    editing: Boolean,
    editingText: String,
    onEditingText: (String) -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRename: () -> Unit,
    onToggleVisibility: () -> Unit,
    onRemove: () -> Unit,
    view: Seq3ViewState,
    // WP12: fragment-only controls (null/no-op for a note row). [fragmentKind] non-null is what
    // gates rendering the kind dropdown + hide-operator-word toggle below — a note has neither.
    fragmentKind: Seq3FragmentKind? = null,
    onSetFragmentKind: ((Seq3FragmentKind) -> Unit)? = null,
    hideKindLabel: Boolean = false,
    onToggleHideKindLabel: (() -> Unit)? = null,
) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth()
            .clip(CORNER_SM)
            .background(if (selected) tc.abg else if (hovered) tc.hv else Color.Transparent, CORNER_SM)
            .then(if (selected) Modifier.border(1.dp, tc.ac, CORNER_SM) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { onHoverEnter() }
            .onPointerEvent(PointerEventType.Exit) { onHoverExit() }
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (editing) {
                InlineField(
                    value = editingText,
                    onValue = onEditingText,
                    modifier = Modifier.weight(1f).height(24.dp).onFocusChanged { view.textFieldFocused = it.hasFocus },
                    fontSize = 11.sp,
                    onSubmit = onCommitRename,
                    onCancel = onCancelRename,
                )
                SquareIconButton("✓", fontSize = 11.sp, onClick = onCommitRename, size = 20.dp)
                SquareIconButton("×", fontSize = 13.sp, onClick = onCancelRename, size = 20.dp)
            } else {
                Column(
                    Modifier.weight(1f).pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                                    event.changes.none { it.isConsumed }
                                ) {
                                    onSelect()
                                }
                            }
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText(kindWord, color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            AppText(
                                label,
                                color = if (!hidden) tc.tx else tc.ts,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textDecoration = if (hidden) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (messageCount > 0) AppText("×$messageCount messages", color = tc.ts, fontSize = 10.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolbarBtn(
                            label = if (hidden) "Show" else "Hide",
                            icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            showLabel = false,
                            tooltip = if (hidden) "Show on canvas" else "Hide from canvas",
                            active = hidden,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
                            shape = CORNER_SM,
                            onClick = onToggleVisibility,
                        )
                        ToolbarBtn(
                            label = "✎",
                            tooltip = "Rename",
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
                            shape = CORNER_SM,
                            onClick = onRename,
                        )
                        // WP12: fragment kind change + hide-operator-word toggle — mirrors the
                        // lifeline-kind Seq3DropdownButton above (`Seq3LifelineRow`). Absent for a
                        // note row, which has no kind at all.
                        if (fragmentKind != null && onSetFragmentKind != null) {
                            Seq3DropdownButton(
                                label = fragmentKind.name.lowercase(),
                                labelColor = tc.ts,
                                fillColor = tc.p2,
                                menuWidth = 110.dp,
                                fixedHeight = SEQ3_ACTION_BADGE_SIZE,
                            ) { close ->
                                Seq3FragmentKind.entries.forEach { kind ->
                                    Seq3DropdownMenuItem(kind.name.lowercase(), active = kind == fragmentKind) {
                                        onSetFragmentKind(kind)
                                        close()
                                    }
                                }
                            }
                            if (onToggleHideKindLabel != null) {
                                // Deliberately a text badge, not the eye icon used two buttons over
                                // for the fragment's own show/hide-on-canvas toggle — this toggles
                                // only the "$kind: " prefix on the label, not the fragment itself.
                                ToolbarBtn(
                                    label = "Op",
                                    showLabel = true,
                                    tooltip = if (hideKindLabel) {
                                        "Kind word hidden on canvas — click to show it"
                                    } else {
                                        "Kind word shown on canvas — click to hide it"
                                    },
                                    active = hideKindLabel,
                                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                    modifier = Modifier.height(SEQ3_ACTION_BADGE_SIZE),
                                    shape = CORNER_SM,
                                    onClick = onToggleHideKindLabel,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ToolbarBtn(
                            label = "×",
                            tooltip = "Remove",
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
                            shape = CORNER_SM,
                            onClick = onRemove,
                        )
                    }
                }
            }
        }
    }
}

// ── One queue row (spec §04) ────────────────────────────────────────────────────────────────

@Composable
private fun Seq3QueueRow(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    message: Seq3Message,
    visibleIds: List<String>,
) {
    val tc = tc()
    val selected = message.id in view.selection.selectedIds
    val needsTarget = message.state == Seq3State.NEEDS_TARGET
    val hidden = message.visibility == Seq3Visibility.HIDDEN
    val hasHiddenOccurrence = message.occurrences.any { it.visibility == Seq3Visibility.HIDDEN }
    val hovered = view.hoveredMessageId == message.id
    val focused = view.focusedMessageId == message.id
    val collapsedCount = seq3CollapsedOccurrenceCount(message)
    val mergeBackTarget = seq3MergeBackTarget(document, message)
    // Pin controls only make sense against LOG-ORDER adjacency (Seq3Queue's nudge is defined over
    // Seq3Document.messages' own order) — showing them under a different view sort would point at
    // a neighbour that isn't actually adjacent on screen. See this phase's report for the note.
    val pinnable = if (view.sort == Seq3Sort.LOG_ORDER) seq3PinnableDirections(document, message.id) else emptySet()

    Column(
        Modifier.fillMaxWidth()
            .clip(CORNER_SM)
            .background(
                when {
                    focused -> tc.abg
                    selected -> tc.sl
                    needsTarget -> tc.warnBg
                    hovered -> tc.hv
                    else -> Color.Transparent
                },
                CORNER_SM,
            )
            // Row focus is intentionally independent from checkbox selection: the accent
            // outline identifies the row being inspected without changing its checkbox state.
            .then(if (focused) Modifier.border(1.dp, tc.ac, CORNER_SM) else Modifier)
            .drawBehind {
                if (selected) drawRect(color = tc.ac, size = Size(3.dp.toPx(), size.height))
            }
            .onPointerEvent(PointerEventType.Enter) { view.hoveredMessageId = message.id }
            .onPointerEvent(PointerEventType.Exit) { if (view.hoveredMessageId == message.id) view.hoveredMessageId = null }
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.occurrences.size > 1) {
                Seq3OccurrenceToggle(
                    expanded = message.id in view.expandedOccurrenceMessageIds,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    view.expandedOccurrenceMessageIds = if (message.id in view.expandedOccurrenceMessageIds) {
                        view.expandedOccurrenceMessageIds - message.id
                    } else {
                        view.expandedOccurrenceMessageIds + message.id
                    }
                    // Reaching for the toggle makes this row the user's, so a later canvas click
                    // elsewhere can't collapse it out from under them.
                    seq3DisownAutoExpand(view, message.id)
                    runCatching { view.focusRequester.requestFocus() }
                }
            } else {
                Box(Modifier.width(16.dp).padding(top = 2.dp)) {
                    Seq3RowCheckbox(checked = selected) {
                        // Checkbox selection is independent from row/occurrence focus.
                        view.selection = seq3Select(visibleIds, view.selection, message.id, additive = true)
                        view.selectionFromMarquee = false
                        view.selectedCanvasRows = emptySet()
                        view.selectedOccurrenceMessageId = null
                        view.selectedOccurrenceEntryId = null
                        runCatching { view.focusRequester.requestFocus() }
                    }
                }
            }
            // Keep the focus-only row press handler on the message body, not on the row
            // wrapper. The checkbox is a sibling hit target, so its pointer stream cannot reach
            // this handler and can never activate/deactivate the checkbox as a side effect — even
            // with Shift/Cmd/Ctrl held down.
            Column(
                Modifier.weight(1f).pointerInput(message.id) {
                    var lastBodyClickMs = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                                event.changes.none { it.isConsumed }
                            ) {
                                val now = System.currentTimeMillis()
                                val doubleClick = now - lastBodyClickMs <= SEQ3_QUEUE_DOUBLE_CLICK_WINDOW_MS
                                lastBodyClickMs = now
                                val modifiers = event.keyboardModifiers
                                // A plain click must not discard a set built with the row
                                // checkboxes. This is especially important when the next row is
                                // opened/expanded to inspect it: the checked rows are the user's
                                // working set, not a transient focus selection. Shift still keeps
                                // its range-selection meaning; an explicit clear button is
                                // available in the selection actions.
                                val additive = modifiers.isCtrlPressed || modifiers.isMetaPressed ||
                                    ((view.selection.selectedIds.isNotEmpty() || view.selectedOccurrenceIds.isNotEmpty()) &&
                                        !modifiers.isShiftPressed)
                                if (additive && !modifiers.isShiftPressed) {
                                    // Queue-body Cmd/Ctrl-clicks use the same message-row set as a
                                    // canvas marquee. This makes non-contiguous grouping available
                                    // from the side panel too, while a plain queue click remains a
                                    // normal whole-message selection.
                                    val baseRows = view.selection.selectedIds.mapTo(linkedSetOf()) {
                                        Seq3CanvasRowRef(it, occurrenceEntryId = null)
                                    }
                                    val rowRef = Seq3CanvasRowRef(message.id, occurrenceEntryId = null)
                                    val nextRows = if (rowRef in baseRows) baseRows - rowRef else baseRows + rowRef
                                    view.selectedCanvasRows = nextRows
                                    view.selection = Seq3Selection(
                                        selectedIds = nextRows.mapTo(linkedSetOf()) { it.messageId },
                                        anchorId = message.id,
                                    )
                                } else {
                                    view.selection = seq3Select(
                                        visibleIds,
                                        view.selection,
                                        message.id,
                                        additive = additive,
                                        range = modifiers.isShiftPressed,
                                    )
                                    view.selectedCanvasRows = emptySet()
                                }
                                view.selectionFromMarquee = false
                                view.selectedOccurrenceMessageId = null
                                view.selectedOccurrenceEntryId = null
                                view.focusedMessageId = message.id.takeIf { view.selection.selectedIds.isNotEmpty() }
                                if (doubleClick && message.occurrences.size > 1) {
                                    view.expandedOccurrenceMessageIds = if (message.id in view.expandedOccurrenceMessageIds) {
                                        view.expandedOccurrenceMessageIds - message.id
                                    } else {
                                        view.expandedOccurrenceMessageIds + message.id
                                    }
                                    // Same as the toggle above: a deliberate double-click owns it.
                                    seq3DisownAutoExpand(view, message.id)
                                }
                                runCatching { view.focusRequester.requestFocus() }
                            }
                        }
                    }
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Seq3RowPatternLine(message, collapsedCount)
                Seq3RowEndpointsLine(state, session, message)
                Seq3MessageControlsLine(state, session, view, message, pinnable, hidden, mergeBackTarget)
                if (hidden) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Hidden from canvas · evidence kept", color = tc.td, fontSize = 10.sp)
                    }
                } else if (collapsedCount != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText(
                            if (hasHiddenOccurrence) "Some occurrences hidden · evidence kept"
                            else "Collapsed to one arrow · ×$collapsedCount",
                            color = tc.td,
                            fontSize = 10.sp,
                        )
                        AppButton(
                            "Show occurrences",
                            onClick = {
                                state.seq3Sessions.applyCommand(
                                    session.id,
                                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetRepeat(Seq3Repeat.EVERY, message.repeatThreshold)),
                                )
                            },
                            variant = ButtonVariant.Ghost,
                            textColor = tc.ac,
                            horizontalPadding = 0.dp,
                            modifier = Modifier.pointerHoverIcon(
                                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                                overrideDescendants = true,
                            ),
                        )
                    }
                } else if (hasHiddenOccurrence) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        AppText("Some occurrences hidden · evidence kept", color = tc.td, fontSize = 10.sp)
                    }
                }
            }
        }
        if (message.id in view.expandedInfoMessageIds) {
            Seq3MessageInfo(state, session, view, message)
        }
        if (message.occurrences.size > 1 && message.id in view.expandedOccurrenceMessageIds) {
            // Keep the nested list at its content height up to ten rows. A fixed height based on
            // the actual row count avoids leaving a large blank panel below short occurrence lists,
            // while still keeping the list scrollable when it exceeds the ten-row limit.
            val displayedOccurrenceCount = message.occurrences.size.coerceAtMost(10)
            val occurrenceListHeight = SEQ3_SUBMESSAGE_ROW_HEIGHT * displayedOccurrenceCount +
                3.dp * (displayedOccurrenceCount - 1).coerceAtLeast(0)
            Box(
                Modifier.fillMaxWidth()
                    .padding(start = 22.dp, top = 5.dp)
                    .height(occurrenceListHeight),
            ) {
                val submessageListState = rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxWidth().padding(end = 6.dp),
                    state = submessageListState,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(message.occurrences, key = { occurrence -> occurrenceSelectionKey(message.id, occurrence.entryId) }) { occurrence ->
                        Seq3OccurrenceSubRow(state, session, view, message, occurrence)
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(submessageListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

@Composable
private fun Seq3MessageInfo(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    message: Seq3Message,
) {
    var pattern by remember(message.id, message.match.template) { mutableStateOf(message.match.template) }
    var label by remember(message.id, message.labelTemplate) { mutableStateOf(message.labelTemplate) }

    Column(
        Modifier.fillMaxWidth()
            .padding(start = 22.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Seq3InfoFieldLabel("Pattern")
        InlineField(
            value = pattern,
            onValue = { pattern = it },
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().onFocusChanged { view.textFieldFocused = it.hasFocus },
            onSubmit = {
                if (pattern.isNotBlank()) {
                    val match = Seq3Match(
                        tag = message.match.tag,
                        template = pattern,
                        captures = seq3ParseTemplateCaptures(pattern),
                    )
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetPattern(match, pattern)),
                    )
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        Seq3InfoFieldLabel("Label")
        InlineField(
            value = label,
            onValue = { label = it },
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().onFocusChanged { view.textFieldFocused = it.hasFocus },
            onSubmit = {
                if (label.isNotBlank()) {
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetLabel(label)),
                    )
                }
            },
        )
    }
}

@Composable
private fun Seq3InfoFieldLabel(text: String) {
    AppText(text.uppercase(), color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.Medium)
}

private fun occurrenceSelectionKey(messageId: String, entryId: Int): String = "$messageId::$entryId"

@Composable
private fun Seq3OccurrenceToggle(expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tc = tc()
    Box(
        modifier.size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .background(if (expanded) tc.abg else tc.p2, RoundedCornerShape(4.dp))
            .border(1.dp, if (expanded) tc.ac else tc.br, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        DisableSelection { AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 9.sp) }
    }
}

@Composable
private fun Seq3OccurrenceSubRow(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    message: Seq3Message,
    occurrence: Seq3Occurrence,
) {
    val tc = tc()
    val entryId = occurrence.entryId
    val key = occurrenceSelectionKey(message.id, entryId)
    val checked = key in view.selectedOccurrenceIds
    val selected = view.selectedOccurrenceMessageId == message.id && view.selectedOccurrenceEntryId == entryId
    Row(
        Modifier.fillMaxWidth()
            .clip(CORNER_SM)
            .background(if (checked || selected) tc.sl else tc.p2, CORNER_SM)
            .height(SEQ3_SUBMESSAGE_ROW_HEIGHT)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Seq3RowCheckbox(checked) {
            view.selectedOccurrenceIds = if (checked) view.selectedOccurrenceIds - key else view.selectedOccurrenceIds + key
            view.selectionFromMarquee = false
            view.selectedCanvasRows = emptySet()
            view.selectedOccurrenceMessageId = message.id
            view.selectedOccurrenceEntryId = entryId
            view.focusedMessageId = message.id
            runCatching { view.focusRequester.requestFocus() }
        }
        Column(
            Modifier.weight(1f).pointerInput(key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                            event.changes.none { it.isConsumed }
                        ) {
                            // A body click is an exact occurrence focus, not a checkbox action.
                            // Store it in the same exact-row representation the canvas marquee and
                            // Cmd-click use, so the matching submessage is expanded/highlighted
                            // on the diagram while its checkbox stays unchanged.
                            view.selectedCanvasRows = setOf(Seq3CanvasRowRef(message.id, entryId))
                            view.selectionFromMarquee = false
                            // Keep the queue's exact-submessage focus as well as the canvas row
                            // ref.  The former paints this submessage as selected; the latter
                            // expands/highlights only its matching arrow on the diagram.
                            view.selectedOccurrenceMessageId = message.id
                            view.selectedOccurrenceEntryId = entryId
                            view.focusedMessageId = message.id
                            view.scrollRequestId = message.id
                            runCatching { view.focusRequester.requestFocus() }
                        }
                    }
                }
            },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            DisableSelection {
                AppText(
                    occurrence.rawTimestamp.ifBlank { "line $entryId" },
                    color = tc.td,
                    fontSize = 9.sp,
                    fontFamily = MONO,
                    maxLines = 1,
                )
                AppText(occurrence.text, color = tc.tx, fontSize = 10.sp, fontFamily = MONO, maxLines = 2)
            }
        }
        val hidden = occurrence.visibility == Seq3Visibility.HIDDEN
        ToolbarBtn(
            label = if (hidden) "Show occurrence" else "Hide occurrence",
            icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            showLabel = false,
            tooltip = if (hidden) "Show only this occurrence" else "Hide only this occurrence",
            active = hidden,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
            shape = CORNER_SM,
            onClick = {
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.SetOccurrenceVisibility(
                        message.id,
                        occurrence.entryId,
                        if (hidden) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                    ),
                )
            },
        )
        ToolbarBtn(
            label = "Move occurrence out",
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            showLabel = false,
            tooltip = "Move this occurrence out as a separate message",
            onClick = {
                state.seq3Sessions.applyCommand(session.id, Seq3Command.MoveOccurrenceOut(message.id, entryId))
                view.selectedOccurrenceIds = view.selectedOccurrenceIds - key
                view.selectedOccurrenceMessageId = null
                view.selectedOccurrenceEntryId = null
                runCatching { view.focusRequester.requestFocus() }
            },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
            shape = CORNER_SM,
        )
    }
}

/** Item 11's original fix made this a PURE visual indicator with no hit target of its own, because
 *  its `Modifier.clickable` (hardcoded `additive = true`, no shift/⌘ awareness) fired ALONGSIDE the
 *  row's own modifier-aware `pointerInput` Press handler on every click, double-processing it: Press
 *  selected only this row first via the row handler, then the checkbox's own Release-driven toggle
 *  saw the id already selected and (being an unconditional toggle) removed it — so a checkbox click
 *  always emptied the selection instead of independently toggling it.
 *
 *  Phase-5 post-ship fix (item 11 continued): the checkbox regains its own [onClick] — always
 *  additive, matching the confirmed "checkbox click is always additive, no ⌘ needed" decision — now
 *  that [Seq3QueueRow]'s own `pointerInput` checks `event.changes.any { it.isConsumed }` before
 *  acting (see that modifier's own comment), so `clickable`'s consumption of the Press change is
 *  what stops the double-fire this time, instead of removing the checkbox's click handling
 *  entirely. */
@Composable
private fun Seq3RowCheckbox(checked: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .background(if (checked) tc.ac else Color.Transparent, RoundedCornerShape(3.dp))
            .border(1.dp, if (checked) tc.ac else tc.br, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) AppText("✓", color = tc.bg, fontSize = 9.sp)
    }
}

@Composable
private fun Seq3RowPatternLine(message: Seq3Message, collapsedCount: Int?) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)) {
            seq3TemplateSegments(message.match.template).forEach { segment ->
                when (segment) {
                    is Seq3TemplateSegment.Literal -> if (segment.text.isNotEmpty()) {
                        AppText(segment.text, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 1)
                    }
                    is Seq3TemplateSegment.Token -> AppText(
                        "{${segment.name}}", color = tc.ac, fontSize = 11.sp, fontFamily = MONO,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }
        val count = collapsedCount ?: message.occurrences.count { it.visibility == Seq3Visibility.VISIBLE }
        if (count > 1) AppText("×$count", color = tc.ts, fontSize = 10.sp)
    }
}

@Composable
private fun Seq3RowEndpointsLine(
    state: AppState,
    session: Seq3WorkspaceSession,
    message: Seq3Message,
) {
    val tc = tc()
    val document = session.document
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // To is the primary endpoint (WP5): a log line usually means the emitting class is
        // EXECUTING something it was asked to do, so the target is the interesting field — it gets
        // the accent-filled chip. From (the tag the line was scanned under, always reliable) stays
        // the plain secondary chip it always was.
        Seq3EndpointChip(document, message.fromLifelineId, emphasized = false) { lifelineId ->
            state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetFrom(lifelineId)))
        }
        AppText("→", color = tc.td, fontSize = 10.sp)
        if (message.toLifelineId != null) {
            Seq3EndpointChip(document, message.toLifelineId, emphasized = true) { lifelineId ->
                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetTo(lifelineId)))
            }
        } else if (message.kind != Seq3Kind.NOTE) {
            Seq3DropdownButton(
                label = "set target", labelColor = tc.warn, fillColor = tc.warnBg, alwaysFilled = true, menuWidth = 150.dp,
            ) { close ->
                document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                    Seq3DropdownMenuItem(lifeline.name) {
                        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetTo(lifeline.id)))
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun Seq3MessageControlsLine(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    message: Seq3Message,
    pinnable: Set<Seq3PinDirection>,
    hidden: Boolean,
    mergeBackTarget: Seq3Message?,
) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Keep the hide action first on this dedicated row, matching the compact action order in
        // the reference and making parent hide semantics obvious before kind/pin controls.
        Seq3VisibilityButton(state, session, message)
        val infoExpanded = message.id in view.expandedInfoMessageIds
        ToolbarBtn(
            label = "Info",
            icon = Icons.Outlined.Info,
            showLabel = false,
            tooltip = if (infoExpanded) "Hide message information" else "Show message information",
            active = infoExpanded,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
            shape = CORNER_SM,
            onClick = {
                view.expandedInfoMessageIds = if (infoExpanded) {
                    view.expandedInfoMessageIds - message.id
                } else {
                    view.expandedInfoMessageIds + message.id
                }
                runCatching { view.focusRequester.requestFocus() }
            },
        )
        Seq3MessageKindPicker(state, session, message, fixedHeight = SEQ3_ACTION_BADGE_SIZE)
        // WP5: "the auto drawing can not find to/from normally" — a one-click fix for a
        // wrongly-directed arrow instead of two dropdown round-trips (reassign From, reassign To).
        // Disabled (not hidden, so the control never jumps around between rows) exactly when there
        // is nothing to swap — see seq3CanSwapEndpoints's own doc.
        ToolbarBtn(
            label = "⇄",
            tooltip = "Swap the from/to direction of this message",
            enabled = seq3CanSwapEndpoints(message),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
            shape = CORNER_SM,
            onClick = {
                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SwapEndpoints))
            },
        )
        if (pinnable.isNotEmpty()) {
            Seq3PinControls(state, session, message, pinnable)
        }
        if (mergeBackTarget != null) {
            ToolbarBtn(
                label = "Move back",
                tooltip = "Return this moved-out occurrence to its original message group",
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                shape = CORNER_SM,
                onClick = {
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.MoveOccurrenceBack(message.id),
                    )
                },
            )
        }
        Spacer(Modifier.weight(1f))
        // Item 15: "revert to generated" for ONE edited row — only ever shown once this message has
        // actually drifted from what the engine would produce (state derives EDITED from `authoring`,
        // Seq3Message's own doc), so an untouched AUTO/NEEDS_TARGET row never offers it.
        if (message.state == Seq3State.EDITED) {
            AppButton(
                "Revert",
                onClick = { state.seq3Sessions.revertMessage(session.id, message.id) },
                variant = ButtonVariant.Ghost,
                textColor = tc.ac,
                horizontalPadding = 0.dp,
                modifier = Modifier.pointerHoverIcon(
                    PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                    overrideDescendants = true,
                ),
            )
            Spacer(Modifier.width(4.dp))
        }
        Seq3StateWord(message, hidden)
    }
}

/** Per-row message kind control. Notes remain supported for custom-message creation, but are not
 * offered as an option in the compact queue menu. */
@Composable
private fun Seq3MessageKindPicker(
    state: AppState,
    session: Seq3WorkspaceSession,
    message: Seq3Message,
    fixedHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val tc = tc()
    Seq3DropdownButton(
        label = message.kind.name.lowercase(),
        labelColor = tc.ts,
        fillColor = tc.p2,
        menuWidth = 120.dp,
        fixedHeight = fixedHeight,
    ) { close ->
        MESSAGE_KIND_OPTIONS.forEach { kind ->
            Seq3DropdownMenuItem(kind.name.lowercase(), active = kind == message.kind) {
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetKind(kind)),
                )
                close()
            }
        }
    }
}

@Composable
private fun Seq3EndpointChip(document: Seq3Document, lifelineId: String, emphasized: Boolean = false, onReassign: (String) -> Unit) {
    val tc = tc()
    val name = document.lifelines.firstOrNull { it.id == lifelineId }?.name ?: lifelineId
    // Emphasized (the To chip, WP5) reads as a permanently accent-filled control, the same
    // always-filled recipe the "set target" warning chip below already uses for a semantic
    // highlight riding on this same component (see Seq3DropdownButton's own doc on `alwaysFilled`).
    Seq3DropdownButton(
        label = name,
        labelColor = if (emphasized) tc.ac else tc.ts,
        fillColor = if (emphasized) tc.abg else tc.p2,
        alwaysFilled = emphasized,
        menuWidth = 150.dp,
    ) { close ->
        document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
            Seq3DropdownMenuItem(lifeline.name, active = lifeline.id == lifelineId) {
                onReassign(lifeline.id)
                close()
            }
        }
    }
}

@Composable
private fun Seq3PinControls(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message, pinnable: Set<Seq3PinDirection>) {
    val tc = tc()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (Seq3PinDirection.UP in pinnable) {
            ToolbarBtn(
                label = "▲",
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                shape = CORNER_SM,
                onClick = {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.UP))
                },
            )
        }
        if (Seq3PinDirection.DOWN in pinnable) {
            ToolbarBtn(
                label = "▼",
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                shape = CORNER_SM,
                onClick = {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.DOWN))
                },
            )
        }
        if (message.orderPin != null) {
            ToolbarBtn(
                label = "pinned",
                active = true,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                shape = CORNER_SM,
                onClick = { state.seq3Sessions.applyCommand(session.id, Seq3Command.ClearPin(message.id)) },
            )
        } else {
            AppText("same ms", color = tc.td, fontSize = 9.sp)
        }
    }
}

@Composable
private fun Seq3VisibilityButton(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    val hidden = message.visibility == Seq3Visibility.HIDDEN
    ToolbarBtn(
        label = if (hidden) "Show message" else "Hide message",
        icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
        showLabel = false,
        tooltip = if (hidden) "Show this message and all of its occurrences" else "Hide this message and all of its occurrences",
        active = hidden,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
        shape = CORNER_SM,
        onClick = {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(setOf(message.id), if (hidden) Seq3BulkAction.Show else Seq3BulkAction.Hide),
            )
        },
    )
}

@Composable
private fun Seq3StateWord(message: Seq3Message, hidden: Boolean) {
    val tc = tc()
    val (label, color) = when {
        hidden -> "hidden" to tc.td
        message.state == Seq3State.NEEDS_TARGET -> "needs target" to tc.warn
        message.state == Seq3State.EDITED -> "edited" to tc.ok
        else -> "auto" to tc.ts
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (message.state == Seq3State.EDITED) Box(Modifier.size(5.dp).background(tc.ok, RoundedCornerShape(50)))
        AppText(
            label.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            textDecoration = if (hidden) TextDecoration.LineThrough else null,
        )
    }
}

/** Finds the exact group recorded by [MoveOccurrenceOut]. The action stays hidden when that group
 * is no longer present or its endpoints/kind have become incompatible. */
private fun seq3MergeBackTarget(document: Seq3Document, message: Seq3Message): Seq3Message? {
    if (message.authoring != Seq3Authoring.EDITED || message.occurrences.size != 1) return null
    val targetId = message.movedOutFromMessageId ?: return null
    return document.messages.singleOrNull { candidate ->
        candidate.id == targetId &&
            candidate.occurrences.isNotEmpty() &&
            candidate.fromLifelineId == message.fromLifelineId &&
            candidate.toLifelineId == message.toLifelineId &&
            candidate.kind == message.kind
    }
}

// ── Pure helpers — testable without a composition (Seq3QueuePanelTest) ─────────────────────────

/** True only when [messageId]'s first occurrence genuinely ties with an immediate LOG-ORDER
 *  neighbour — surfaces [nudgeSeq3OrderPin]'s own validity check (a dry run, its returned document
 *  is discarded) rather than reimplementing the tie rule, per this phase's brief. */
internal fun seq3PinnableDirections(document: Seq3Document, messageId: String): Set<Seq3PinDirection> =
    Seq3PinDirection.entries.filterTo(linkedSetOf()) { direction -> nudgeSeq3OrderPin(document, messageId, direction).applied }

/** Mirrors [Seq3BulkAction.SwapEndpoints]'s own eligibility filter exactly (a NOTE has no `to`; a
 *  message with no target has nothing to swap FROM) — the WP5 `⇄` control is disabled, never
 *  hidden, for the same rows the bulk action itself would leave untouched, so the control never
 *  jumps around between rows as a selection changes kind/target. */
internal fun seq3CanSwapEndpoints(message: Seq3Message): Boolean =
    message.kind != Seq3Kind.NOTE && message.toLifelineId != null

/** Non-null exactly when [message] is currently drawn as one collapsed/badged arrow (spec §04's
 *  third inset row line) — the same condition Seq3Layout's own `expandForLayout` uses for
 *  [Seq3Repeat.COLLAPSE_ABOVE]. */
internal fun seq3CollapsedOccurrenceCount(message: Seq3Message): Int? =
    message.occurrences.count { it.visibility == Seq3Visibility.VISIBLE }.let { visibleCount ->
        if (message.repeat == Seq3Repeat.COLLAPSE_ABOVE && visibleCount > message.repeatThreshold) visibleCount else null
    }

internal sealed class Seq3TemplateSegment {
    data class Literal(val text: String) : Seq3TemplateSegment()

    data class Token(val name: String) : Seq3TemplateSegment()
}

private val TEMPLATE_TOKEN = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

/** Splits a match/label template into literal runs and `{token}` slots, for the pattern line's
 *  accent-highlighted rendering (spec §04). Pure and independent of [Seq3Message] so
 *  Seq3QueuePanelTest can exercise it directly. */
internal fun seq3TemplateSegments(template: String): List<Seq3TemplateSegment> {
    val segments = mutableListOf<Seq3TemplateSegment>()
    var cursor = 0
    TEMPLATE_TOKEN.findAll(template).forEach { match ->
        if (match.range.first > cursor) segments += Seq3TemplateSegment.Literal(template.substring(cursor, match.range.first))
        segments += Seq3TemplateSegment.Token(match.groupValues[1])
        cursor = match.range.last + 1
    }
    if (cursor < template.length) segments += Seq3TemplateSegment.Literal(template.substring(cursor))
    return segments
}

/** Parses `{name}` tokens out of a freely-typed template (the row info field) into
 *  [com.indagium.diagram3.Seq3Capture]s with [com.indagium.diagram3.Seq3CaptureSource.AUTHOR] — the
 *  source reserved for "a capture a user names by hand" (Seq3Model.kt's own doc on that enum
 *  value). Duplicate token names keep only their first occurrence. */
internal fun seq3ParseTemplateCaptures(template: String): List<com.indagium.diagram3.Seq3Capture> =
    TEMPLATE_TOKEN.findAll(template).map { it.groupValues[1] }.distinct()
        .map { com.indagium.diagram3.Seq3Capture(it, com.indagium.diagram3.Seq3CaptureSource.AUTHOR) }
        .toList()
